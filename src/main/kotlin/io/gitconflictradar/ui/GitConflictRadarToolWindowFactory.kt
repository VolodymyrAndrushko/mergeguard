package io.gitconflictradar.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.TitledSeparator
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import com.intellij.ui.DoubleClickListener
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBImageIcon
import io.gitconflictradar.core.GitConflictRadarListener
import io.gitconflictradar.core.GitConflictRadarService
import io.gitconflictradar.core.GitConflictRadarTopics
import io.gitconflictradar.model.ConflictWarning
import io.gitconflictradar.model.RepositorySnapshot
import io.gitconflictradar.model.FetchStatus
import io.gitconflictradar.settings.GitConflictRadarSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.BoxLayout
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.Icon



class GitConflictRadarToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<GitConflictRadarService>()
        val model = DefaultListModel<GitConflictRadarListItem>()
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                ): Component {
                    return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                        if (this is JLabel) {
                            if (value is GitConflictRadarListItem.RepositoryHeader) {
                                icon = com.intellij.icons.AllIcons.Vcs.Branch
                                if (!isSelected) {
                                    foreground = JBColor.namedColor("Label.foreground", JBColor(0x111111, 0xEEEEEE))
                                }
                            } else if (value is GitConflictRadarListItem.WarningItem) {
                                val isReal = value.warning.isRealConflict
                                icon = if (isReal) com.intellij.icons.AllIcons.General.Error else com.intellij.icons.AllIcons.General.Warning
                                if (!isSelected) {
                                    foreground = if (isReal) JBColor(0xEF4444, 0xF87171) else JBColor(0xD97706, 0xFFB74D)
                                }
                            }
                        }
                    }
                }
            }
            border = JBUI.Borders.empty(6)
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val index = list.locationToIndex(event.point)
                if (index < 0) return false
                val item = model.getElementAt(index)
                if (item is GitConflictRadarListItem.WarningItem) {
                    openConflictDiff(project, service, item)
                    return true
                }
                return false
            }
        }.installOn(list)

        val summary = JLabel("Discovering Git repositories…")
        val loaderIcon = AsyncProcessIcon("GitConflictRadarLoading").apply {
            border = JBUI.Borders.emptyRight(8)
            isVisible = false
        }
        val summaryPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(summary, BorderLayout.CENTER)
            add(loaderIcon, BorderLayout.EAST)
        }

        val settings = GitConflictRadarSettings.getInstance()
        val state = settings.state

        val fetchCb = JBCheckBox("Enable Background Fetch", state.backgroundFetchEnabled).apply {
            addActionListener {
                state.backgroundFetchEnabled = isSelected
                service.refresh(fetch = isSelected)
            }
        }

        val autoRefreshCb = JBCheckBox("Auto-refresh on File Edit", state.autoRefreshOnSave).apply {
            addActionListener {
                state.autoRefreshOnSave = isSelected
            }
        }

        val lineMarkersCb = JBCheckBox("Show Editor Gutter Warnings", state.showLineMarkers).apply {
            addActionListener {
                state.showLineMarkers = isSelected
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
            }
        }

        val projectDecoratorCb = JBCheckBox("Show Project Tree Warnings", state.showProjectViewDecorator).apply {
            addActionListener {
                state.showProjectViewDecorator = isSelected
                com.intellij.ide.projectView.ProjectView.getInstance(project).refresh()
            }
        }

        val showBlocksCb = JBCheckBox("Show Editor Conflict Blocks", state.showInlayCodeBlocks).apply {
            addActionListener {
                state.showInlayCodeBlocks = isSelected
                project.service<GitConflictRadarInlayManager>().refreshInlays()
            }
        }

        val modelSpinner = SpinnerNumberModel(state.maxBranchesToAnalyze, 5, 200, 5)
        val spinner = JSpinner(modelSpinner).apply {
            addChangeListener {
                if (value as Int != state.maxBranchesToAnalyze) {
                    state.maxBranchesToAnalyze = value as Int
                    service.refresh(fetch = false)
                }
            }
        }

        val branchLimitPanel = JPanel(BorderLayout(4, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel("Max branches to scan:"), BorderLayout.WEST)
            add(spinner, BorderLayout.CENTER)
        }

        val reportBugButton = com.intellij.ui.components.labels.LinkLabel<Any>("Report a Bug", null).apply {
            setListener({ _, _ ->
                ReportBugDialog(project).show()
            }, null)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val settingsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)

            add(TitledSeparator("Configuration"))
            add(fetchCb)
            add(autoRefreshCb)
            add(lineMarkersCb)
            add(projectDecoratorCb)
            add(showBlocksCb)
            add(JBUI.Borders.empty(4, 0).wrap(branchLimitPanel))
            add(JBUI.Borders.empty(4, 0).wrap(reportBugButton))
        }

        val panel = JPanel(BorderLayout()).apply {
            add(summaryPanel, BorderLayout.NORTH)
            add(com.intellij.ui.components.JBScrollPane(list), BorderLayout.CENTER)
            add(settingsPanel, BorderLayout.SOUTH)
        }

        fun render(snapshots: List<RepositorySnapshot>) {
            model.clear()
            snapshots.forEach { snapshot ->
                model.addElement(GitConflictRadarListItem.RepositoryHeader(snapshot))
                snapshot.warnings.forEach { warning ->
                    model.addElement(GitConflictRadarListItem.WarningItem(warning, snapshot.root))
                }
            }
            val warningCount = snapshots.sumOf { it.warnings.size }
            val realCount = snapshots.sumOf { snap -> snap.warnings.count { it.isRealConflict } }
            val potentialCount = warningCount - realCount
            val conflictSummary = buildList {
                if (realCount > 0) add("$realCount real")
                if (potentialCount > 0) add("$potentialCount potential")
            }.joinToString(", ").ifEmpty { "0" }
            summary.text = when (snapshots.size) {
                0 -> "No Git repositories found in this project."
                1 -> "1 repository monitored · $conflictSummary conflict${if (warningCount == 1) "" else "s"}"
                else -> "${snapshots.size} repositories monitored · $conflictSummary conflicts"
            }
            val isRefreshing = snapshots.any { it.fetchStatus == FetchStatus.FETCHING }
            if (isRefreshing) {
                loaderIcon.isVisible = true
                loaderIcon.resume()
            } else {
                loaderIcon.isVisible = false
                loaderIcon.suspend()
            }

            // Sync controls with latest settings state (e.g. if modified from global settings dialog)
            val currentState = GitConflictRadarSettings.getInstance().state
            if (fetchCb.isSelected != currentState.backgroundFetchEnabled) {
                fetchCb.isSelected = currentState.backgroundFetchEnabled
            }
            if (autoRefreshCb.isSelected != currentState.autoRefreshOnSave) {
                autoRefreshCb.isSelected = currentState.autoRefreshOnSave
            }
            if (lineMarkersCb.isSelected != currentState.showLineMarkers) {
                lineMarkersCb.isSelected = currentState.showLineMarkers
            }
            if (projectDecoratorCb.isSelected != currentState.showProjectViewDecorator) {
                projectDecoratorCb.isSelected = currentState.showProjectViewDecorator
            }
            if (showBlocksCb.isSelected != currentState.showInlayCodeBlocks) {
                showBlocksCb.isSelected = currentState.showInlayCodeBlocks
            }
            if (spinner.value != currentState.maxBranchesToAnalyze) {
                spinner.value = currentState.maxBranchesToAnalyze
            }

            // Update badge on the stripe button icon safely and force repaint
            try {
                val hasReal = snapshots.any { snap -> snap.warnings.any { it.isRealConflict } }
                toolWindow.setIcon(createNumberIcon(warningCount, hasReal))
                com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)?.repaint()
            } catch (e: Throwable) {
                // Prevent runtime class cast or platform rendering exception from crashing content panel load
            }
        }
        render(service.currentSnapshots())
        project.messageBus.connect(toolWindow.disposable).subscribe(
            GitConflictRadarTopics.SNAPSHOTS_CHANGED,
            GitConflictRadarListener { snapshots ->
                ApplicationManager.getApplication().invokeLater { render(snapshots) }
            },
        )
        // A visible tool window is an explicit user request for current remote state.
        service.refresh(fetch = true)

        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    private fun openConflictDiff(
        project: Project,
        service: GitConflictRadarService,
        item: GitConflictRadarListItem.WarningItem
    ) {
        val warning = item.warning
        val root = item.root
        val branch = warning.conflictingBranch
        val base = warning.mergeBase
        if (base.isNullOrBlank()) return

        val relativePath = warning.filePath
        val projectRoot = project.basePath?.let { java.nio.file.Path.of(it) } ?: root
        val absolutePath = projectRoot.resolve(relativePath)
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(absolutePath) ?: return
        val repoRelativePath = root.relativize(absolutePath).toString().replace('\\', '/')

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
}


