package io.tylermmorton.magefile

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project

class MageConfigurationFactory(private val runCfgType: MageRunConfigurationType) :
    ConfigurationFactory(runCfgType) {

    override fun getId() = MageRunConfigurationType.ID

    override fun getName(): String = runCfgType.displayName

    override fun createTemplateConfiguration(project: Project) =
        MageRunConfiguration(project, this, "Mage")
}
