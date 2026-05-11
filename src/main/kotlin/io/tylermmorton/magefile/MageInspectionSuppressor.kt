package io.tylermmorton.magefile

import com.goide.psi.GoFile
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoMethodDeclaration
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement

private val SUPPRESSED_IDS = setOf("GoUnusedExportedFunction", "GoUnusedFunction")

class MageInspectionSuppressor : InspectionSuppressor {

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in SUPPRESSED_IDS) return false
        return isMageTarget(element) || isMageTarget(element.parent)
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        SuppressQuickFix.EMPTY_ARRAY

    private fun isMageTarget(element: PsiElement?): Boolean {
        val goFile = element?.containingFile as? GoFile ?: return false
        if (!isMageFile(goFile)) return false

        return when (element) {
            is GoFunctionDeclaration -> {
                val name = element.name ?: return false
                name.isNotEmpty() && name[0].isUpperCase() && hasValidReturnType(element)
            }
            is GoMethodDeclaration -> {
                val name = element.name ?: return false
                if (name.isEmpty() || name[0].isLowerCase()) return false
                if (!hasValidReturnType(element)) return false
                val receiverTypeName = getReceiverTypeName(element) ?: return false
                isNamespaceType(goFile, receiverTypeName)
            }
            else -> false
        }
    }

    private fun isMageFile(file: GoFile): Boolean {
        val text = file.text ?: return false
        return text.contains("//go:build mage") || text.contains("// +build mage")
    }

    private fun hasValidReturnType(declaration: PsiElement): Boolean {
        val signature = when (declaration) {
            is GoFunctionDeclaration -> declaration.signature
            is GoMethodDeclaration -> declaration.signature
            else -> return false
        } ?: return true

        val result = signature.result ?: return true
        val resultText = result.text.trim().removePrefix("(").removeSuffix(")").trim()
        return resultText.isEmpty() || resultText == "error"
    }

    private fun getReceiverTypeName(fn: GoMethodDeclaration): String? {
        val receiver = fn.receiver ?: return null
        val typeText = receiver.type?.text?.trim() ?: return null
        return typeText.removePrefix("*").trim()
    }

    private fun isNamespaceType(file: GoFile, typeName: String): Boolean {
        val fileText = file.text ?: return false
        val pattern = Regex("""type\s+${Regex.escape(typeName)}\s+mg\.Namespace""")
        return pattern.containsMatchIn(fileText)
    }
}