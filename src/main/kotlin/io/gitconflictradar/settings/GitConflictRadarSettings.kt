package io.gitconflictradar.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "GitConflictRadarSettings", storages = [Storage("gitconflictradar.xml")])
@Service(Service.Level.APP)
class GitConflictRadarSettings : PersistentStateComponent<GitConflictRadarSettings.State> {
    data class State(
        var backgroundFetchEnabled: Boolean = true,
        var fetchIntervalMinutes: Int = 5,
        var showLineMarkers: Boolean = true,
        var showProjectViewDecorator: Boolean = true,
        var autoRefreshOnSave: Boolean = true,
        var maxBranchesToAnalyze: Int = 5,
        var showInlayCodeBlocks: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): GitConflictRadarSettings =
            ApplicationManager.getApplication().getService(GitConflictRadarSettings::class.java)
    }
}
