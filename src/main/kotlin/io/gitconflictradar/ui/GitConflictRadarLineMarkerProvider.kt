package io.gitconflictradar.ui

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.icons.AllIcons
import com.intellij.util.Function
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ColoredListCellRenderer
import io.gitconflictradar.core.GitConflictRadarService
import io.gitconflictradar.model.ConflictWarning
import io.gitconflictradar.settings.GitConflictRadarSettings
import javax.swing.JList

/** Places one orange gutter warning in a risky file; hover it to inspect the other branch's diff, and click to open diff. */
class GitConflictRadarLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (!GitConflictRadarSettings.getInstance().state.showLineMarkers) return null
        if (element.textRange.startOffset != 0) return null
        val file = element.containingFile?.virtualFile ?: return null
        val project = element.project
        val warnings = project.service<GitConflictRadarService>().warningsFor(file)
        if (warnings.isEmpty()) return null

        val navHandler = GutterIconNavigationHandler<PsiElement> { _, elem ->
            val virtualFile = elem.containingFile?.virtualFile ?: return@GutterIconNavigationHandler
            val proj = elem.project
            val service = proj.service<GitConflictRadarService>()
            val fileWarnings = service.warningsFor(virtualFile)
            if (fileWarnings.isEmpty()) return@GutterIconNavigationHandler

            val snapshot = service.currentSnapshots().find { snap ->
                try {
                    virtualFile.toNioPath().startsWith(snap.root)
                } catch (_: Exception) {
                    false
                }
            } ?: return@GutterIconNavigationHandler

            if (fileWarnings.size == 1) {
                openConflictDiff(proj, service, snapshot.root, virtualFile, fileWarnings.first())
            } else {
                val popup = JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(fileWarnings)
                    .setTitle("Select Branch to Show Diff")
                    .setRenderer(object : ColoredListCellRenderer<ConflictWarning>() {
                        override fun customizeCellRenderer(
                            list: JList<out ConflictWarning>, value: ConflictWarning?, index: Int, selected: Boolean, hasFocus: Boolean
                        ) {
                            if (value != null) {
                                append(value.conflictingBranch)
                                value.author?.let { append(" ($it)", SimpleTextAttributes.GRAY_ATTRIBUTES) }
                            }
                        }
                    })
                    .setItemChosenCallback { warning ->
                        openConflictDiff(proj, service, snapshot.root, virtualFile, warning)
                    }
                    .createPopup()
                
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(proj).selectedTextEditor
                if (editor != null) {
                    popup.showInBestPositionFor(editor)
                } else {
                    popup.showInFocusCenter()
                }
            }
        }

        val hasReal = warnings.any { it.isRealConflict }
        val icon = if (hasReal) AllIcons.General.Error else AllIcons.General.Warning

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            Function { tooltip(warnings) },
            navHandler,
            GutterIconRenderer.Alignment.LEFT,
        )
    }

    private fun openConflictDiff(
        project: Project,
        service: GitConflictRadarService,
        root: java.nio.file.Path,
        virtualFile: VirtualFile,
        warning: ConflictWarning
    ) {
        val branch = warning.conflictingBranch
        val base = warning.mergeBase
        if (base.isNullOrBlank()) return

        val repoRelativePath = root.relativize(virtualFile.toNioPath()).toString().replace('\\', '/')

        ApplicationManager.getApplication().executeOnPooledThread {
            val baseContentText = service.git(root, "show", "$base:$repoRelativePath") ?: ""
            val branchContentText = service.git(root, "show", "$branch:$repoRelativePath") ?: ""

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val factory = DiffContentFactory.getInstance()
                val fileType = virtualFile.fileType
                val baseContent = factory.create(project, baseContentText, fileType)
                val branchContent = factory.create(project, branchContentText, fileType)
                val request = SimpleDiffRequest(
                    "Conflict Branch Changes for ${virtualFile.name}",
                    baseContent,
                    branchContent,
                    "Common Ancestor ($base)",
                    "Conflict Branch ($branch)"
                )
                request.putUserData(GIT_CONFLICT_RADAR_VIRTUAL_FILE, virtualFile)
                DiffManager.getInstance().showDiff(project, request)
            }
        }
    }

    private fun tooltip(warnings: List<ConflictWarning>): String {
        val first = warnings.first()
        val hasReal = warnings.any { it.isRealConflict }
        val severity = if (hasReal) "Real merge conflict" else "Potential merge conflict"
        val sources = warnings.joinToString(", ") { it.conflictingBranch }
        val diff = first.remoteDiff.orEmpty().ifBlank { "Git could not produce a textual diff for this file. Click icon to see full diff." }
        return "<html><b>$severity</b><br>Changed on: ${escape(sources)}<br><br>" +
            "<b>Changes from ${escape(first.conflictingBranch)}:</b><br><pre>${escape(diff)}</pre></html>"
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
