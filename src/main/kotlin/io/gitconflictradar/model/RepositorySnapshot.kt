package io.gitconflictradar.model

import java.nio.file.Path
import java.time.Instant

data class RepositorySnapshot(
    val root: Path,
    val branch: String?,
    val upstream: String?,
    val remotes: List<String>,
    val fetchedAt: Instant?,
    val fetchStatus: FetchStatus,
    val message: String? = null,
    val warnings: List<ConflictWarning> = emptyList(),
)

enum class FetchStatus {
    NOT_FETCHED,
    FETCHING,
    SUCCESS,
    FAILED,
}

data class ConflictWarning(
    val filePath: String,
    /** Branch that also changed this file after its common ancestor with the current branch. */
    val conflictingBranch: String,
    val risk: ConflictRisk = ConflictRisk.HIGH,
    val commit: String? = null,
    val author: String? = null,
    /** Zero-context Git diff from the other branch, kept short for editor tooltips. */
    val remoteDiff: String? = null,
    val mergeBase: String? = null,
    val isRealConflict: Boolean = false,
)

enum class ConflictRisk {
    HIGH,
}
