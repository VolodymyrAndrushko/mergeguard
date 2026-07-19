package io.gitconflictradar.core

import com.intellij.util.messages.Topic
import io.gitconflictradar.model.RepositorySnapshot

fun interface GitConflictRadarListener {
    fun snapshotsUpdated(snapshots: List<RepositorySnapshot>)
}

object GitConflictRadarTopics {
    @Topic.ProjectLevel
    val SNAPSHOTS_CHANGED: Topic<GitConflictRadarListener> = Topic.create("GitConflictRadar repository snapshots", GitConflictRadarListener::class.java)
}

