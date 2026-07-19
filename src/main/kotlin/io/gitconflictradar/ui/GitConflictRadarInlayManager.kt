package io.gitconflictradar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import io.gitconflictradar.core.GitConflictRadarService
import io.gitconflictradar.model.ConflictWarning
import io.gitconflictradar.settings.GitConflictRadarSettings
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class GitConflictRadarInlayManager(private val project: Project) : Disposable {
    companion object {
        private val INLAYS_KEY = Key.create<MutableList<Inlay<*>>>("GitConflictRadarInlays")
        private val GENERATION_KEY = Key.create<Int>("GitConflictRadarInlayGeneration")
        private val MOUSE_LISTENER_ADDED_KEY = Key.create<Boolean>("GitConflictRadarMouseListener")
    }

    private val clickAlarm = com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val editors = source.getEditors(file).filterIsInstance<TextEditor>()
                    for (textEditor in editors) {
                        updateInlaysForEditor(textEditor.editor, file)
                    }
                }
            }
        )
    }

    data class HunkConflict(val currentLineIndex: Int, val originalLines: List<String>)

    fun refreshInlays() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val fileEditorManager = FileEditorManager.getInstance(project)
            val openEditors = fileEditorManager.allEditors.filterIsInstance<TextEditor>()
            for (textEditor in openEditors) {
                updateInlaysForEditor(textEditor.editor, textEditor.file)
            }
        }
    }

    fun updateInlaysForEditor(editor: Editor, file: VirtualFile) {
        // Increment generation to cancel any previously scheduled background updates
        val gen = (editor.getUserData(GENERATION_KEY) ?: 0) + 1
        editor.putUserData(GENERATION_KEY, gen)
        
        if (editor.getUserData(MOUSE_LISTENER_ADDED_KEY) != true) {
            editor.putUserData(MOUSE_LISTENER_ADDED_KEY, true)
            editor.addEditorMouseListener(object : com.intellij.openapi.editor.event.EditorMouseListener {
                override fun mouseClicked(e: com.intellij.openapi.editor.event.EditorMouseEvent) {
                    if (e.isConsumed) return
                    val point = e.mouseEvent.point
                    val inlays = editor.getUserData(INLAYS_KEY) ?: return
                    for (inlay in inlays) {
                        val bounds = inlay.bounds ?: continue
                        if (bounds.contains(point)) {
                            val renderer = inlay.renderer as? ConflictBlockRenderer ?: continue
                            if (e.mouseEvent.clickCount == 2) {
                                clickAlarm.cancelAllRequests()
                                // Double click: open diff
                                val firstWarning = renderer.uniqueConflicts.firstOrNull()?.third
                                if (firstWarning != null) {
                                    val svc = project.service<GitConflictRadarService>()
                                    svc.showDiffForWarning(svc.currentSnapshots().find { snap -> 
                                        try { file.toNioPath().startsWith(snap.root) } catch (_: Exception) { false } 
                                    }?.root ?: return, firstWarning)
                                }
                            } else if (e.mouseEvent.clickCount == 1) {
                                clickAlarm.cancelAllRequests()
                                clickAlarm.addRequest({
                                    // Single click: toggle collapse
                                    renderer.isCollapsed = !renderer.isCollapsed
                                    inlay.update()
                                }, 250)
                            }
                            e.consume()
                            return
                        }
                    }
                }
            })
        }

        val settings = GitConflictRadarSettings.getInstance().state
        val showBlocks = settings.showInlayCodeBlocks

        val service = project.service<GitConflictRadarService>()
        val warnings = service.warningsFor(file)
        if (warnings.isEmpty() || !showBlocks) {
            // If there are no warnings or code block overlays are disabled, we must clear old inlays!
            ApplicationManager.getApplication().invokeLater {
                if (gen == editor.getUserData(GENERATION_KEY)) {
                    val oldInlays = editor.getUserData(INLAYS_KEY)
                    if (oldInlays != null) {
                        oldInlays.forEach { Disposer.dispose(it) }
                        oldInlays.clear()
                    }
                }
            }
            return
        }

        val snapshot = service.currentSnapshots().find { snap ->
            try {
                file.toNioPath().startsWith(snap.root)
            } catch (_: Exception) {
                false
            }
        } ?: return

        val repoRelativePath = snapshot.root.relativize(file.toNioPath()).toString().replace('\\', '/')

        // Run git diff in background
        ApplicationManager.getApplication().executeOnPooledThread {
            val allConflicts = mutableListOf<Triple<String, HunkConflict, ConflictWarning>>()
            for (warning in warnings) {
                val branchName = warning.conflictingBranch
                val base = warning.mergeBase
                val diffRange = if (!base.isNullOrBlank()) "$base..$branchName" else branchName
                val diffOutput = service.git(snapshot.root, "diff", "-U0", diffRange, "--", repoRelativePath) ?: ""
                val hunkConflicts = parseHunkConflicts(diffOutput)

                for (hc in hunkConflicts) {
                    allConflicts.add(Triple(branchName, hc, warning))
                }
            }

            if (allConflicts.isNotEmpty()) {
                // Group conflicts by their target line index to avoid duplicates/overlaps on the same line
                val grouped = allConflicts.groupBy { it.second.currentLineIndex }

                // Only proceed if this is still the latest generation
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (gen != editor.getUserData(GENERATION_KEY)) return@invokeLater

                    // Clear old inlays on EDT right before rendering the new ones
                    val oldInlays = editor.getUserData(INLAYS_KEY)
                    if (oldInlays != null) {
                        oldInlays.forEach { Disposer.dispose(it) }
                        oldInlays.clear()
                    } else {
                        editor.putUserData(INLAYS_KEY, mutableListOf())
                    }

                    val list = editor.getUserData(INLAYS_KEY) ?: return@invokeLater
                    val document = editor.document

                    for ((line, conflictsAtLine) in grouped) {
                        if (line >= 0 && line < document.lineCount) {
                            val lineStartOffset = document.getLineStartOffset(line)

                            // Deduplicate conflicts that have identical original lines text across different branches
                            val uniqueConflicts = mutableListOf<Triple<String, HunkConflict, ConflictWarning>>()
                            val seenLines = mutableSetOf<List<String>>()
                            for (c in conflictsAtLine) {
                                if (seenLines.add(c.second.originalLines)) {
                                    uniqueConflicts.add(c)
                                } else {
                                    // If we've seen this exact diff before, we append the branch name to the existing entry
                                    val existingIndex = uniqueConflicts.indexOfFirst { it.second.originalLines == c.second.originalLines }
                                    if (existingIndex >= 0) {
                                        val existing = uniqueConflicts[existingIndex]
                                        uniqueConflicts[existingIndex] = Triple(
                                            "${existing.first}, ${c.first}",
                                            existing.second,
                                            // Upgrade warning if the new one is real
                                            if (!existing.third.isRealConflict && c.third.isRealConflict) c.third else existing.third
                                        )
                                    }
                                }
                            }

                            // Add single combined block inlay containing conflicting code segments
                            val blockRenderer = ConflictBlockRenderer(editor, uniqueConflicts)
                            val blockInlay = editor.inlayModel.addBlockElement(lineStartOffset, true, true, 100, blockRenderer)
                            if (blockInlay != null) {
                                list.add(blockInlay)
                            }
                        }
                    }
                }
            }
        }
    }

    private class ConflictBlockRenderer(
        private val editor: Editor,
        val uniqueConflicts: List<Triple<String, HunkConflict, ConflictWarning>>
    ) : EditorCustomElementRenderer {
        var isCollapsed = false

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
            val metrics = editor.component.getFontMetrics(font)
            var maxWidth = 100
            for ((branchName, hc, warning) in uniqueConflicts) {
                val contributor = listOfNotNull(warning.author, warning.commit).joinToString(" · ")
                val label = if (warning.isRealConflict) "Conflict Branch" else "Potential Conflict"
                val collapseState = if (isCollapsed) "[+]" else "[-]"
                val headerText = "$collapseState <<<< $label [$branchName]${if (contributor.isBlank()) "" else " ($contributor)"}"
                maxWidth = maxWidth.coerceAtLeast(metrics.stringWidth(headerText))
                if (!isCollapsed) {
                    for (l in hc.originalLines) {
                        maxWidth = maxWidth.coerceAtLeast(metrics.stringWidth(l) + 16)
                    }
                }
            }
            return maxWidth
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val lineHeight = editor.lineHeight
            var totalLines = 0
            for ((_, hc, _) in uniqueConflicts) {
                totalLines += if (isCollapsed) 1 else hc.originalLines.size + 2
            }
            return totalLines * lineHeight
        }

        override fun paint(
            inlay: Inlay<*>,
            g: java.awt.Graphics,
            targetRegion: java.awt.Rectangle,
            textAttributes: TextAttributes
        ) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
                g2.font = font
                val fm = g2.fontMetrics
                val lineHeight = editor.lineHeight
                val isReal = uniqueConflicts.any { it.third.isRealConflict }

                val bg = if (isReal) {
                    com.intellij.ui.JBColor(java.awt.Color(254, 242, 242), java.awt.Color(69, 26, 26))
                } else {
                    com.intellij.ui.JBColor(java.awt.Color(255, 248, 230), java.awt.Color(80, 50, 20))
                }
                g2.color = bg
                g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

                val themeColor = if (isReal) com.intellij.ui.JBColor.RED else com.intellij.ui.JBColor.ORANGE
                g2.color = themeColor
                g2.stroke = java.awt.BasicStroke(2f)
                g2.drawLine(targetRegion.x + 2, targetRegion.y, targetRegion.x + 2, targetRegion.y + targetRegion.height)

                var currentY = targetRegion.y + fm.ascent

                for ((branchName, hc, warning) in uniqueConflicts) {
                    g2.color = themeColor
                    val contributor = listOfNotNull(warning.author, warning.commit).joinToString(" · ")
                    val label = if (warning.isRealConflict) "Conflict Branch" else "Potential Conflict"
                    val collapseState = if (isCollapsed) "[+]" else "[-]"
                    val headerText = "$collapseState <<<< $label [$branchName]${if (contributor.isBlank()) "" else " ($contributor)"}"
                    g2.drawString(headerText, targetRegion.x + 8, currentY)
                    currentY += lineHeight

                    if (!isCollapsed) {
                        g2.color = com.intellij.ui.JBColor.namedColor("Editor.foreground", com.intellij.ui.JBColor(0x333333, 0xCCCCCC))
                        for (l in hc.originalLines) {
                            g2.drawString(l, targetRegion.x + 16, currentY)
                            currentY += lineHeight
                        }

                        g2.color = themeColor
                        g2.drawString("====", targetRegion.x + 8, currentY)
                        currentY += lineHeight
                    }
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private fun parseHunkConflicts(diffOutput: String): List<HunkConflict> {
        val conflicts = mutableListOf<HunkConflict>()
        val lines = diffOutput.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("@@")) {
                val parts = line.split(" ")
                if (parts.size >= 3) {
                    val oldRange = parts[1]
                    val rangeParts = oldRange.removePrefix("-").split(",")
                    val startLine = rangeParts[0].toIntOrNull()
                    if (startLine != null) {
                        val currentLineIndex = (startLine - 1).coerceAtLeast(0)
                        val originalLines = mutableListOf<String>()
                        i++
                        var deletedLinesCount = 0
                        while (i < lines.size && !lines[i].startsWith("@@")) {
                            val diffLine = lines[i]
                            if (diffLine.startsWith("+") && !diffLine.startsWith("+++")) {
                                originalLines.add(diffLine.substring(1))
                            } else if (diffLine.startsWith("-") && !diffLine.startsWith("---")) {
                                deletedLinesCount++
                            }
                            i++
                        }
                        if (originalLines.isEmpty() && deletedLinesCount > 0) {
                            originalLines.add("[Code deleted by branch]")
                        }
                        if (originalLines.isNotEmpty()) {
                            conflicts.add(HunkConflict(currentLineIndex, originalLines))
                        }
                        continue
                    }
                }
            }
            i++
        }
        return conflicts
    }

    override fun dispose() = Unit
}
