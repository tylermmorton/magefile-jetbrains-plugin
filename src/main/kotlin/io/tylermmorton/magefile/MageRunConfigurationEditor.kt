package io.tylermmorton.magefile

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

class MageRunConfigurationEditor(private val project: Project) :
    SettingsEditor<MageRunConfiguration>() {

    private val mageExecutableField = TextFieldWithBrowseButton()
    private val magefileDirField = TextFieldWithBrowseButton()
    private val targetField = ExpandableTextField()
    private val argumentsField = ExpandableTextField()
    private val workingDirectoryField = TextFieldWithBrowseButton()
    private val envVarsComponent = EnvironmentVariablesComponent()
    private val debugCompilePathField = TextFieldWithBrowseButton()

    private val panel by lazy {
        FormBuilder.createFormBuilder()
            .setAlignLabelOnRight(false)
            .setHorizontalGap(UIUtil.DEFAULT_HGAP)
            .setVerticalGap(UIUtil.DEFAULT_VGAP)
            .addLabeledComponent("Mage executable", mageExecutableField)
            .addLabeledComponent("Magefiles directory", magefileDirField)
            .addLabeledComponent("Target", targetField)
            .addComponent(LabeledComponent.create(argumentsField, "CLI arguments"))
            .addLabeledComponent("Working directory", workingDirectoryField)
            .addComponent(envVarsComponent)
            .addLabeledComponent("Debug binary path (optional)", debugCompilePathField)
            .panel
    }

    init {
        mageExecutableField.addBrowseFolderListener(
            TextBrowseFolderListener(MageExecutableFileChooserDescriptor(), project)
        )
        magefileDirField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
            )
        )
        workingDirectoryField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
            )
        )
        debugCompilePathField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
            )
        )
    }

    override fun createEditor(): JComponent = panel

    override fun resetEditorFrom(cfg: MageRunConfiguration) {
        mageExecutableField.text = cfg.magePath
        magefileDirField.text = cfg.magefileDir
        targetField.text = cfg.target
        argumentsField.text = cfg.arguments
        workingDirectoryField.text = cfg.workingDirectory
        envVarsComponent.envData = cfg.environmentVariables
        debugCompilePathField.text = cfg.debugCompilePath
    }

    override fun applyEditorTo(cfg: MageRunConfiguration) {
        cfg.magePath = mageExecutableField.text
        cfg.magefileDir = magefileDirField.text
        cfg.target = targetField.text
        cfg.arguments = argumentsField.text
        cfg.workingDirectory = workingDirectoryField.text
        cfg.environmentVariables = envVarsComponent.envData
        cfg.debugCompilePath = debugCompilePathField.text
    }
}
