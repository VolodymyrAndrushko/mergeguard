package io.gitconflictradar.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.openapi.components.service
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN
import com.intellij.ide.projectView.PresentationData
import io.gitconflictradar.core.GitConflictRadarService
import io.gitconflictradar.settings.GitConflictRadarSettings

/** Adds an orange or red warning icon and label to files that GitConflictRadar considers high risk. */
class GitConflictRadarProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        if (!GitConflictRadarSettings.getInstance().state.showProjectViewDecorator) return
        val file = node.virtualFile ?: return
        val service = node.project.service<GitConflictRadarService>()
        val warnings = service.warningsFor(file)
        if (warnings.isEmpty()) return

        val hasReal = warnings.any { it.isRealConflict }
        if (hasReal) {
            data.setIcon(AllIcons.General.Error)
            data.addText(
                "  real merge conflict",
                SimpleTextAttributes(STYLE_PLAIN, JBColor(0xEF4444, 0xF87171)),
            )
        } else {
            data.setIcon(AllIcons.General.Warning)
            data.addText(
                "  potential merge conflict",
                SimpleTextAttributes(STYLE_PLAIN, JBColor(0xD97706, 0xFFB74D)),
            )
        }
    }
}
