package io.tylermmorton.magefile

import com.intellij.openapi.fileChooser.FileChooserDescriptor

class MageExecutableFileChooserDescriptor :
    FileChooserDescriptor(true, false, false, false, false, false) {
    init {
        title = "Select Mage Executable"
        withFileFilter { file ->
            file.name.equals("mage", ignoreCase = true) ||
                file.name.equals("mage.exe", ignoreCase = true)
        }
    }
}
