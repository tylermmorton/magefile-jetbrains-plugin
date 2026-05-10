package io.tylermmorton.magefile

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.net.ServerSocket

class MageDebugProgramRunner : ProgramRunner<RunnerSettings> {

    companion object {
        const val RUNNER_ID = "MageDebugRunner"
        private const val NOTIFICATION_GROUP = "Mage"
        private val LOG = Logger.getInstance(MageDebugProgramRunner::class.java)
    }

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is MageRunConfiguration

    override fun execute(environment: ExecutionEnvironment) {
        val cfg = environment.runProfile as MageRunConfiguration
        val project = environment.project

        // All blocking work (compile, dlv launch, stderr wait) must run off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            executeInBackground(cfg, project, environment)
        }
    }

    private fun executeInBackground(
        cfg: MageRunConfiguration,
        project: Project,
        environment: ExecutionEnvironment,
    ) {
        // Validate dlv is present before doing any work
        if (!isDlvAvailable()) {
            notifyError(
                project,
                "Delve (dlv) not found on PATH. Install it with: go install github.com/go-delve/delve/cmd/dlv@latest",
            )
            return
        }

        val tmpBinary = resolveBinaryPath(cfg)
        val workDir = resolveWorkDir(cfg, project)

        // --- Stage 1: compile magefiles to a binary with debug symbols ---
        // Point MAGEFILE_CACHE at the magefiles directory itself so mage_output_file.go
        // lands at $workDir/mage_output_file.go. This path is what the binary's DWARF debug
        // info references, so the debugger can map execution back to source. Using workDir
        // directly (rather than a subdirectory) means GoLand already watches the directory —
        // we just need one explicit VFS refresh to register the new file before dlv starts.

        // GOFLAGS is space-split by the Go toolchain; each entry must be a single token.
        // "all=" would apply -N to every dependency, which breaks nosplit functions in some
        // third-party packages (e.g. pjbgf/sha1cd) that exceed the stack limit without inlining.
        // Scoping to "main=" applies the flag only to the generated mage main package, which
        // is sufficient for debugging user-authored magefiles.
        val compileEnv = buildEnvMap(cfg) + mapOf(
            "GOFLAGS" to "-gcflags=main=-N",
            "MAGEFILE_CACHE" to workDir,
        )
        val compileCmd =
            GeneralCommandLine()
                .withExePath(cfg.magePath.ifEmpty { "mage" })
                .withWorkDirectory(workDir)
                .withEnvironment(compileEnv)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                // -keep tells mage to preserve the generated bootstrap source in its cache
                // directory rather than deleting it after compilation. The compiled binary's
                // DWARF info references that file, so the debugger needs it on disk to show
                // source and resolve breakpoints correctly.
                .withParameters("-keep", "-compile", tmpBinary)

        val compileProcess = compileCmd.createProcess()
        val exitCode = compileProcess.waitFor()
        if (exitCode != 0) {
            val stderr = compileProcess.errorStream.bufferedReader().readText()
            notifyError(project, "mage -compile failed (exit $exitCode):\n$stderr")
            return
        }

        // Locate the generated bootstrap file for VFS registration and cleanup.
        val mageOutputFile = java.io.File("$workDir/mage_output_file.go").takeIf { it.exists() }

        // Force GoLand's VFS to register mage_output_file.go synchronously before dlv starts.
        // Without this the debugger connects before the IDE knows the file exists, causing
        // source mapping to fail when stepping through generated bootstrap code.
        if (mageOutputFile != null) {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(mageOutputFile.path)
        }

        // --- Stage 2: find a free port and start Delve in headless mode ---
        val port = findFreePort()
        val dlvCmd =
            GeneralCommandLine()
                .withExePath("dlv")
                .withWorkDirectory(workDir)
                .withParameters(
                    "exec", tmpBinary,
                    "--headless",
                    "--accept-multiclient",
                    "--api-version=2",
                    "--check-go-version=false",
                    "--listen=127.0.0.1:$port",
                    "--", cfg.target,
                )

        LOG.info("mage debug: binary=$tmpBinary port=$port dlv=${dlvCmd.commandLineString}")

        val dlvProcess = dlvCmd.createProcess()

        // Wait for Delve to signal it has bound its listen port.
        val (probeOk, probeMsg) = waitForDelve(dlvProcess, port, timeoutMs = 15_000)
        if (!probeOk) {
            notifyError(
                project,
                "Delve did not start. ${if (!dlvProcess.isAlive) "Process exited early." else "Timed out."}\n$probeMsg",
            )
            return
        }

        LOG.info("mage debug: Delve ready on port $port")

        // When the Delve process exits (debug session ends), delete the compiled binary
        // and the generated bootstrap source.
        Thread {
            dlvProcess.waitFor()
            java.io.File(tmpBinary).delete()
            mageOutputFile?.delete()
        }.also { it.isDaemon = true; it.name = "mage-debug-cleanup" }.start()

        // --- Stage 3: connect GoLand's Go Remote debugger ---
        connectGoRemoteDebugger(project, port, environment, tmpBinary)
    }

    private fun resolveBinaryPath(cfg: MageRunConfiguration): String {
        if (cfg.debugCompilePath.isNotEmpty()) {
            return cfg.debugCompilePath
        }
        val safeName = cfg.target.replace(':', '-').ifEmpty { "default" }
        return "${System.getProperty("java.io.tmpdir")}/mage-debug-$safeName"
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    /**
     * Waits for Delve to print its "API server listening at: ..." line.
     * Delve writes this to stdout; errors go to stderr. We drain both streams on
     * daemon threads so neither blocks, and watch for the ready signal on either.
     * Returns Pair(success, diagnosticMessage).
     */
    private fun waitForDelve(process: Process, port: Int, timeoutMs: Long): Pair<Boolean, String> {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        var readyLine = ""

        fun drainStream(stream: java.io.InputStream) = Thread {
            try {
                stream.bufferedReader().forEachLine { line ->
                    lines.add(line)
                    if (line.contains("API server listening") || line.contains(":$port")) {
                        readyLine = line
                    }
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }

        drainStream(process.inputStream)   // stdout — "API server listening at: ..."
        drainStream(process.errorStream)   // stderr — error messages

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (readyLine.isNotEmpty()) return Pair(true, readyLine)
            if (!process.isAlive) {
                Thread.sleep(200) // let drain threads flush remaining output
                return Pair(false, "process exited. output: ${lines.joinToString(" | ")}")
            }
            Thread.sleep(100)
        }
        return Pair(false, "timed out after ${timeoutMs}ms. output so far: ${lines.joinToString(" | ")}")
    }

    private fun isDlvAvailable(): Boolean {
        return try {
            val result = GeneralCommandLine("dlv", "version").createProcess().waitFor()
            result == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun connectGoRemoteDebugger(
        project: Project,
        port: Int,
        environment: ExecutionEnvironment,
        binaryPath: String,
    ) {
        // Look up the Go plugin's "Go Remote" configuration type. The registered ID has varied
        // across GoLand versions so we try known IDs first, then fall back to a display-name
        // search across all registered configuration types.
        val goRemoteType = findGoRemoteConfigurationType()

        if (goRemoteType == null) {
            notifyError(
                project,
                "Could not find Go Remote run configuration type. " +
                    "Please create a Go Remote configuration manually pointing to 127.0.0.1:$port",
            )
            return
        }

        ApplicationManager.getApplication().invokeLater {
            // Create a temporary Go Remote config — not registered in the RunManager so it does
            // not appear as a separate entry under "Go Remote" in the Run/Debug Configurations tree.
            val runManager = RunManager.getInstance(project)
            val configName = "Mage Debug: ${(environment.runProfile as MageRunConfiguration).target}"
            val factory = goRemoteType.configurationFactories[0]
            val settings = runManager.createConfiguration(configName, factory)

            // Set host/port on the Go Remote config using reflection since the class
            // is in the Go plugin's internal API space
            val remoteCfg = settings.configuration
            LOG.info("mage debug: Go Remote cfg class=${remoteCfg.javaClass.name}")
            try {
                remoteCfg.javaClass.getMethod("setHost", String::class.java)
                    .invoke(remoteCfg, "127.0.0.1")
                remoteCfg.javaClass.getMethod("setPort", Int::class.javaPrimitiveType)
                    .invoke(remoteCfg, port)
                LOG.info("mage debug: Go Remote configured for 127.0.0.1:$port")
            } catch (ex: Exception) {
                // If reflection fails, the existing config values (or defaults) will be used.
                // Fallback: notify the user with connection details so they can connect manually.
                notifyError(
                    project,
                    "Could not configure Go Remote automatically (${ex.javaClass.simpleName}: ${ex.message}). " +
                        "Delve is listening on 127.0.0.1:$port — connect a Go Remote configuration manually.",
                )
            }

            try {
                val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()
                ExecutionEnvironmentBuilder.create(debugExecutor, settings).buildAndExecute()
            } catch (ex: ExecutionException) {
                notifyError(project, "Failed to launch Go Remote debugger: ${ex.message}")
            }
        }
    }

    /**
     * Finds the Go plugin's "Go Remote" configuration type. The registered ID has changed
     * across GoLand versions, so we try several known IDs and then fall back to a
     * display-name search across all registered types.
     */
    private fun findGoRemoteConfigurationType(): ConfigurationType? {
        val knownIds = listOf(
            "GoRemoteDebugConfigurationType", // GoLand 2024+
            "GoRemoteRunConfiguration",       // pre-2024 GoLand
            "GoRemoteDebugConfiguration",     // seen in some builds
        )
        for (id in knownIds) {
            try {
                return ConfigurationTypeUtil.findConfigurationType(id)
            } catch (_: Exception) {
                // try next
            }
        }
        // Last resort: match by display name (e.g. "Go Remote")
        return ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            .firstOrNull { it.displayName.contains("Go Remote", ignoreCase = true) }
    }

    private fun notifyError(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }
}
