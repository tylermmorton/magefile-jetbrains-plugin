package io.tylermmorton.magefile

import com.intellij.execution.configurations.ConfigurationTypeBase

class MageRunConfigurationType :
    ConfigurationTypeBase(ID, "Mage", "Mage build target run configuration", MagePluginIcons.Mage) {

    companion object {
        const val ID = "MageRunConfiguration"
    }

    init {
        addFactory(MageConfigurationFactory(this))
    }
}
