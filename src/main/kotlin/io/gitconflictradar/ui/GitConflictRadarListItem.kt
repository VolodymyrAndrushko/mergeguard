package io.gitconflictradar.ui

import io.gitconflictradar.model.RepositorySnapshot
import io.gitconflictradar.model.ConflictWarning
import io.gitconflictradar.model.FetchStatus

sealed interface GitConflictRadarListItem {
    data class RepositoryHeader(val snapshot: RepositorySnapshot) : GitConflictRadarListItem {
        override fun toString(): String {
            val branch = snapshot.branch ?: "detached HEAD"
            val upstream = snapshot.upstream?.let { " → $it" } ?: ""
            val state = snapshot.fetchStatus.name.lowercase().replace('_', ' ')
            val lastFetched = snapshot.fetchedAt?.let {
                val time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(it)
                "  |  updated $time"
            } ?: ""
            val errorMsg = if (!snapshot.message.isNullOrBlank()) "  |  ⚠️ ${snapshot.message}" else ""
            return "${snapshot.root.fileName}  |  $branch$upstream  |  $state$lastFetched$errorMsg"
        }
    }

    data class WarningItem(val warning: ConflictWarning, val root: java.nio.file.Path) : GitConflictRadarListItem {
        override fun toString(): String {
            val contributor = listOfNotNull(warning.author, warning.commit).joinToString(" · ")
            val contributorPart = if (contributor.isBlank()) "" else "($contributor)  "
            val reversedPath = warning.filePath.split('/').asReversed().joinToString("/") + "/"
            return "  $contributorPart${warning.conflictingBranch}  →  $reversedPath"
        }
    }
}
