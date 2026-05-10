package io.tylermmorton.magefile

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import org.jdom.Element
import java.io.File

class MageRunConfiguration(
    project: Project,
    factory: MageConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    /** Path to the mage binary. Empty means use mage from PATH. */
    var magePath = ""

    /**
     * Directory containing the magefiles. Mage discovers *.go files with //go:build mage
     * in its working directory automatically — there is no -f <file> flag.
     */
    var magefileDir = ""

    /** Target name as passed to mage on the CLI, e.g. "build" or "ns:deploy". */
    var target = ""

    /** Extra CLI arguments appended after the target name. */
    var arguments = ""

    /** Explicit working directory override. Defaults to magefileDir. */
    var workingDirectory = ""

    var environmentVariables: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT

    /**
     * Output path for the debug-compiled binary. Empty = system temp dir.
     * Only used when running via the debug executor.
     */
    var debugCompilePath = ""

    private companion object {
        const val MAGE_ELEM = "mage"
        const val ATTR_MAGE_PATH = "magePath"
        const val ATTR_MAGEFILE_DIR = "magefileDir"
        const val ATTR_TARGET = "target"
        const val ATTR_ARGUMENTS = "arguments"
        const val ATTR_WORKING_DIR = "workingDirectory"
        const val ATTR_DEBUG_COMPILE_PATH = "debugCompilePath"
    }

    override fun checkConfiguration() {
        if (target.isEmpty()) {
            throw RuntimeConfigurationError("Target is not set")
        }
    }

    override fun getConfigurationEditor() = MageRunConfigurationEditor(project)

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        val child = element.getOrCreateChild(MAGE_ELEM)
        child.setAttribute(ATTR_MAGE_PATH, magePath)
        child.setAttribute(ATTR_MAGEFILE_DIR, magefileDir)
        child.setAttribute(ATTR_TARGET, target)
        child.setAttribute(ATTR_ARGUMENTS, arguments)
        child.setAttribute(ATTR_WORKING_DIR, workingDirectory)
        child.setAttribute(ATTR_DEBUG_COMPILE_PATH, debugCompilePath)
        environmentVariables.writeExternal(child)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        val mageElem = element.getChild(MAGE_ELEM) ?: return
        magePath = mageElem.getAttributeValue(ATTR_MAGE_PATH) ?: ""
        magefileDir = mageElem.getAttributeValue(ATTR_MAGEFILE_DIR) ?: ""
        target = mageElem.getAttributeValue(ATTR_TARGET) ?: ""
        arguments = mageElem.getAttributeValue(ATTR_ARGUMENTS) ?: ""
        workingDirectory = mageElem.getAttributeValue(ATTR_WORKING_DIR) ?: ""
        debugCompilePath = mageElem.getAttributeValue(ATTR_DEBUG_COMPILE_PATH) ?: ""
        environmentVariables = EnvironmentVariablesData.readExternal(mageElem)
    }

    override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState {
        return object : CommandLineState(env) {
            override fun startProcess(): ProcessHandler {
                val workDir = resolveWorkDir(this@MageRunConfiguration, project)
                val envs = buildEnvMap(this@MageRunConfiguration)

                val params = ParametersList()
                if (target.isNotEmpty()) {
                    params.addParametersString(target)
                }
                if (arguments.isNotEmpty()) {
                    params.addParametersString(arguments)
                }

                val cmd =
                    GeneralCommandLine()
                        .withExePath(magePath.ifEmpty { "mage" })
                        .withWorkDirectory(workDir)
                        .withEnvironment(envs)
                        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                        .withParameters(params.list)

                val processHandler = ColoredProcessHandler(cmd)
                processHandler.setShouldKillProcessSoftly(true)
                ProcessTerminatedListener.attach(processHandler)
                return processHandler
            }
        }
    }
}

/**
 * Resolves the working directory for a run configuration:
 *   1. Explicit workingDirectory override
 *   2. magefileDir (where mage will discover magefiles)
 *   3. Project base path as fallback
 */
fun resolveWorkDir(cfg: MageRunConfiguration, project: Project): String {
    val macroManager = PathMacroManager.getInstance(project)
    val resolvedMagefileDir = macroManager.expandPath(cfg.magefileDir)
    return when {
        cfg.workingDirectory.isNotEmpty() -> macroManager.expandPath(cfg.workingDirectory)
        resolvedMagefileDir.isNotEmpty() -> resolvedMagefileDir
        else -> project.basePath ?: "."
    }
}

/**
 * Builds the environment map for a run configuration, merging the parent
 * environment (if passParentEnvs is set) with the user-configured variables.
 */
fun buildEnvMap(cfg: MageRunConfiguration): Map<String, String> {
    val parentEnvs =
        if (cfg.environmentVariables.isPassParentEnvs) EnvironmentUtil.getEnvironmentMap()
        else emptyMap()
    return parentEnvs + cfg.environmentVariables.envs.toMutableMap()
}
