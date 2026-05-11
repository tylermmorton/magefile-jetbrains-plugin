package io.tylermmorton.magefile

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoNamedElement
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class MageLineMarkerProvider : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        // getInfo is called for every PSI leaf node in every open Go file.
        // Fast-path exit: only act when the element's parent is a function or method declaration.
        val parent = element.parent ?: return null
        val namedElement: GoNamedElement =
            when (parent) {
                is GoFunctionDeclaration -> parent
                is GoMethodDeclaration -> parent
                else -> return null
            }

        // Only fire on the single identifying identifier (the function name token).
        // Without this check we'd produce a duplicate icon for every child PSI node.
        if (element != namedElement.getIdentifyingElement()) {
            return null
        }

        // Only process Go files that carry the mage build tag.
        val goFile = element.containingFile as? GoFile ?: return null
        if (!isMageFile(goFile)) {
            return null
        }

        // Mage targets must be exported (start with uppercase).
        val funcName = namedElement.name ?: return null
        if (funcName.isEmpty() || funcName[0].isLowerCase()) {
            return null
        }

        // Validate that the return type (if any) is only "error".
        if (!hasValidReturnType(parent)) {
            return null
        }

        // Build the CLI target name and derive the magefiles directory.
        val magefileDir = goFile.virtualFile?.parent?.path ?: return null
        val targetName: String =
            when (parent) {
                is GoFunctionDeclaration -> funcName.lowercase()
                is GoMethodDeclaration -> {
                    val receiverTypeName = getReceiverTypeName(parent) ?: return null
                    // Only include methods whose receiver type is declared as mg.Namespace.
                    if (!isNamespaceType(goFile, receiverTypeName)) return null
                    "${receiverTypeName.lowercase()}:${funcName.lowercase()}"
                }
                else -> return null
            }

        val actions = arrayOf(
            MageRunAction(targetName, element.project, magefileDir),
            MageDebugAction(targetName, element.project, magefileDir),
        )
        return Info(AllIcons.Actions.Execute, actions) { "Run Mage: $targetName" }
    }

    /**
     * Returns true if the file contains a mage build tag.
     * Supports both modern (//go:build mage) and legacy (// +build mage) styles.
     */
    private fun isMageFile(file: GoFile): Boolean {
        val text = file.text ?: return false
        return text.contains("//go:build mage") || text.contains("// +build mage")
    }

    /**
     * Returns true if the function/method has a valid mage return type:
     * either no return value at all, or a single "error" return.
     */
    private fun hasValidReturnType(declaration: PsiElement): Boolean {
        val signature =
            when (declaration) {
                is GoFunctionDeclaration -> declaration.signature
                is GoMethodDeclaration -> declaration.signature
                else -> return false
            } ?: return true // no signature means func() — valid

        val result = signature.resultType ?: return true // no return — valid
        return result.text.trim() == "error"
    }

    /**
     * Returns the receiver type name, stripping pointer decoration.
     * e.g. "*Build" -> "Build"
     */
    private fun getReceiverTypeName(fn: GoMethodDeclaration): String? {
        val receiver = fn.receiver ?: return null
        val typeText = receiver.type?.text?.trim() ?: return null
        return typeText.removePrefix("*").trim()
    }

    /**
     * Returns true if the given type name is declared as `type <name> mg.Namespace` in the file.
     * Uses a text-based search to avoid fragile Go type-resolution APIs.
     */
    private fun isNamespaceType(file: GoFile, typeName: String): Boolean {
        val fileText = file.text ?: return false
        val pattern = Regex("""type\s+${Regex.escape(typeName)}\s+mg\.Namespace""")
        return pattern.containsMatchIn(fileText)
    }

    private class MageRunAction(
        private val target: String,
        private val project: Project,
        private val magefileDir: String,
    ) : AnAction("Run Mage: $target", "Run Mage target: $target", AllIcons.Actions.Execute) {

        override fun actionPerformed(e: AnActionEvent) {
            val settings = findOrCreateConfig(target, project, magefileDir)
            val runConfig = settings.configuration as MageRunConfiguration
            runConfig.target = target
            runConfig.magefileDir = magefileDir
            if (runConfig.workingDirectory.isEmpty()) {
                runConfig.workingDirectory = magefileDir
            }

            val runManager = RunManager.getInstance(project)
            if (runManager.findConfigurationByName(settings.name) == null) {
                runManager.addConfiguration(settings)
            }
            runManager.selectedConfiguration = settings

            try {
                val executor =
                    ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)
                if (executor != null) {
                    ExecutionEnvironmentBuilder.create(executor, settings).buildAndExecute()
                }
            } catch (ex: ExecutionException) {
                ex.printStackTrace()
            }
        }
    }

    private class MageDebugAction(
        private val target: String,
        private val project: Project,
        private val magefileDir: String,
    ) : AnAction("Debug Mage: $target", "Debug Mage target: $target", AllIcons.Actions.StartDebugger) {

        override fun actionPerformed(e: AnActionEvent) {
            val settings = findOrCreateConfig(target, project, magefileDir)
            val runConfig = settings.configuration as MageRunConfiguration
            runConfig.target = target
            runConfig.magefileDir = magefileDir
            if (runConfig.workingDirectory.isEmpty()) {
                runConfig.workingDirectory = magefileDir
            }

            val runManager = RunManager.getInstance(project)
            if (runManager.findConfigurationByName(settings.name) == null) {
                runManager.addConfiguration(settings)
            }
            runManager.selectedConfiguration = settings

            try {
                val executor = DefaultDebugExecutor.getDebugExecutorInstance()
                ExecutionEnvironmentBuilder.create(executor, settings).buildAndExecute()
            } catch (ex: ExecutionException) {
                ex.printStackTrace()
            }
        }
    }
}

/** Finds an existing "Mage: <target>" run configuration or creates a new one. */
private fun findOrCreateConfig(target: String, project: Project, magefileDir: String) =
    RunManager.getInstance(project).let { runManager ->
        val configName = "Mage: $target"
        runManager.findConfigurationByName(configName)
            ?: run {
                val factory = MageRunConfigurationType().configurationFactories[0]
                runManager.createConfiguration(configName, factory)
            }
    }
