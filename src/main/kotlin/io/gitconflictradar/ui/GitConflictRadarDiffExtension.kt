package io.gitconflictradar.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import io.gitconflictradar.core.GitConflictRadarService
import io.gitconflictradar.model.ConflictWarning
import javax.swing.JComponent

val GIT_CONFLICT_RADAR_VIRTUAL_FILE = com.intellij.openapi.util.Key.create<VirtualFile>("GitConflictRadarVirtualFile")

class GitConflictRadarDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return

        val virtualFile = request.getUserData(GIT_CONFLICT_RADAR_VIRTUAL_FILE)
            ?: (request as? ContentDiffRequest)?.contents?.mapNotNull { content ->
                when (content) {
                    is com.intellij.diff.contents.FileContent -> content.file
                    is com.intellij.diff.contents.DocumentContent -> FileDocumentManager.getInstance().getFile(content.document)
                    else -> null
                }
            }?.firstOrNull() ?: return

        // Register keyboard shortcuts on the viewer component to open the targeted file in the editor
        val openFileAction = java.awt.event.ActionListener {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && virtualFile.isValid) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            }
        }

        val strokeCtrl = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, java.awt.event.InputEvent.CTRL_DOWN_MASK)
        val strokeMeta = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, java.awt.event.InputEvent.META_DOWN_MASK)
        val strokeF4 = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, 0)

        val component = viewer.component
        component.registerKeyboardAction(openFileAction, strokeCtrl, javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
        component.registerKeyboardAction(openFileAction, strokeMeta, javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
        component.registerKeyboardAction(openFileAction, strokeF4, javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)

        val service = project.service<GitConflictRadarService>()
        val warnings = service.warningsFor(virtualFile)
        if (warnings.isEmpty()) return

        val editors = mutableListOf<Editor>()

        // Safely extract editors from the viewer
        if (viewer is com.intellij.diff.tools.simple.SimpleDiffViewer) {
            editors.add(viewer.editor1)
            editors.add(viewer.editor2)
        } else if (viewer is com.intellij.diff.tools.fragmented.UnifiedDiffViewer) {
            editors.add(viewer.editor)
        }

        if (editors.isEmpty()) return

        val snapshot = service.currentSnapshots().find { snap ->
            try {
                virtualFile.toNioPath().startsWith(snap.root)
            } catch (_: Exception) {
                false
            }
        } ?: return

        for (editor in editors) {
            val panel = createNotificationPanel(project, service, snapshot.root, virtualFile, warnings)
            editor.headerComponent = panel
        }
    }

    private fun createNotificationPanel(
        project: com.intellij.openapi.project.Project,
        service: GitConflictRadarService,
        root: java.nio.file.Path,
        virtualFile: VirtualFile,
        warnings: List<ConflictWarning>
    ): JComponent {
        val panel = EditorNotificationPanel()
        val branches = warnings.joinToString(", ") { it.conflictingBranch }
        panel.text = "GitConflictRadar: Potential conflict with branch(es): $branches"

        val relativePath = root.relativize(virtualFile.toNioPath()).toString().replace('\\', '/')

        for (warning in warnings) {
            val branch = warning.conflictingBranch
            val base = warning.mergeBase
            if (!base.isNullOrBlank()) {
                panel.createActionLabel("Show changes on '$branch'") {
                    com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                        val baseContentText = service.git(root, "show", "$base:$relativePath") ?: ""
                        val branchContentText = service.git(root, "show", "$branch:$relativePath") ?: ""

                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed) return@invokeLater
                            val factory = com.intellij.diff.DiffContentFactory.getInstance()
                            val fileType = virtualFile.fileType
                            val baseContent = factory.create(project, baseContentText, fileType)
                            val branchContent = factory.create(project, branchContentText, fileType)
                            val request = com.intellij.diff.requests.SimpleDiffRequest(
                                "Conflict Branch Changes for ${virtualFile.name}",
                                baseContent,
                                branchContent,
                                "Common Ancestor ($base)",
                                "Conflict Branch ($branch)"
                            )
                            request.putUserData(GIT_CONFLICT_RADAR_VIRTUAL_FILE, virtualFile)
                            com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
                        }
                    }
                }
            }
        }
        panel.createActionLabel("Refresh") {
            service.refresh(fetch = false)
        }
        return panel
    }
}
