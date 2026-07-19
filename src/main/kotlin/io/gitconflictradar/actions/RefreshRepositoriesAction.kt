package io.gitconflictradar.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import io.gitconflictradar.core.GitConflictRadarService

class RefreshRepositoriesAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.service<GitConflictRadarService>()?.refresh(fetch = true)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }
}

