package io.gitconflictradar.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class GitConflictRadarSettingsConfigurable : Configurable {
    private val backgroundFetch = JBCheckBox("Fetch remotes in the background")
    private val intervalMinutes = JBTextField()
    private val autoRefreshOnSave = JBCheckBox("Auto-refresh on file edit")
    private val showLineMarkers = JBCheckBox("Show editor gutter warnings")
    private val showProjectViewDecorator = JBCheckBox("Show project tree warning labels")
    private val showInlayCodeBlocks = JBCheckBox("Show conflict code blocks in editor (Red blocks)")
    private val maxBranchesToAnalyze = JBTextField()
    private var panel: JPanel? = null

    override fun getDisplayName() = "GitConflictRadar"

    override fun createComponent(): JComponent {
        intervalMinutes.columns = 6
        maxBranchesToAnalyze.columns = 6
        panel = FormBuilder.createFormBuilder()
            .addComponent(backgroundFetch)
            .addLabeledComponent("Fetch interval (minutes):", intervalMinutes)
            .addComponent(autoRefreshOnSave)
            .addComponent(showLineMarkers)
            .addComponent(showProjectViewDecorator)
            .addComponent(showInlayCodeBlocks)
            .addLabeledComponent("Max branches to scan:", maxBranchesToAnalyze)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val state = GitConflictRadarSettings.getInstance().state
        return backgroundFetch.isSelected != state.backgroundFetchEnabled ||
            intervalMinutes.text.toIntOrNull() != state.fetchIntervalMinutes ||
            autoRefreshOnSave.isSelected != state.autoRefreshOnSave ||
            showLineMarkers.isSelected != state.showLineMarkers ||
            showProjectViewDecorator.isSelected != state.showProjectViewDecorator ||
            showInlayCodeBlocks.isSelected != state.showInlayCodeBlocks ||
            maxBranchesToAnalyze.text.toIntOrNull() != state.maxBranchesToAnalyze
    }

    override fun apply() {
        val state = GitConflictRadarSettings.getInstance().state
        state.backgroundFetchEnabled = backgroundFetch.isSelected
        state.fetchIntervalMinutes = intervalMinutes.text.toIntOrNull()?.coerceIn(1, 120) ?: 5
        state.autoRefreshOnSave = autoRefreshOnSave.isSelected
        state.showLineMarkers = showLineMarkers.isSelected
        state.showProjectViewDecorator = showProjectViewDecorator.isSelected
        state.showInlayCodeBlocks = showInlayCodeBlocks.isSelected
        state.maxBranchesToAnalyze = maxBranchesToAnalyze.text.toIntOrNull()?.coerceIn(1, 1000) ?: 5
    }

    override fun reset() {
        val state = GitConflictRadarSettings.getInstance().state
        backgroundFetch.isSelected = state.backgroundFetchEnabled
        intervalMinutes.text = state.fetchIntervalMinutes.toString()
        autoRefreshOnSave.isSelected = state.autoRefreshOnSave
        showLineMarkers.isSelected = state.showLineMarkers
        showProjectViewDecorator.isSelected = state.showProjectViewDecorator
        showInlayCodeBlocks.isSelected = state.showInlayCodeBlocks
        maxBranchesToAnalyze.text = state.maxBranchesToAnalyze.toString()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
