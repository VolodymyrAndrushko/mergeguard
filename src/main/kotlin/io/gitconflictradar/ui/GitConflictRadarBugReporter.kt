package io.gitconflictradar.ui

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

class ReportBugDialog(private val project: Project) : DialogWrapper(project, true) {
    private val descriptionArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Report GitConflictRadar Bug"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(JBLabel("Please explain the bug/issue you encountered (Required):"), BorderLayout.NORTH)
        
        descriptionArea.preferredSize = Dimension(450, 150)
        val scrollPane = JBScrollPane(descriptionArea)
        panel.add(scrollPane, BorderLayout.CENTER)
        
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (descriptionArea.text.trim().isEmpty()) {
            return ValidationInfo("Please provide an explanation of the bug.", descriptionArea)
        }
        return null
    }

    override fun doOKAction() {
        val userExplanation = descriptionArea.text.trim()
        val taggedMessage = "[PLUGIN:GitConflictRadar]\n$userExplanation"
        
        // Copy tagged message to clipboard
        try {
            CopyPasteManager.getInstance().setContents(StringSelection(taggedMessage))
        } catch (_: Exception) {}

        // Show submit information alert
        val messageHtml = """
            <html>
            <body>
            <h3>Please send an email to <b>mrkamilot@gmail.com</b>.</h3>
            <p><b>Subject / Message body should include:</b><br/>
            <pre>[PLUGIN:GitConflictRadar]<br/>$userExplanation</pre></p>
            <p><i>(This tagged message has been copied to your clipboard)</i></p>
            <p><b>Please also attach the 'idea.log' file from the log folder.</b></p>
            <p>Click OK to open the IDE log directory in your file explorer.</p>
            </body>\
            </html>
        """.trimIndent()

        Messages.showInfoMessage(project, messageHtml, "Send Bug Report")
        
        // Open the native log folder containing idea.log
        val logDir = File(PathManager.getLogPath())
        if (logDir.exists()) {
            try {
                com.intellij.ide.actions.RevealFileAction.openFile(logDir)
            } catch (_: Exception) {}
        }
        
        super.doOKAction()
    }
}
