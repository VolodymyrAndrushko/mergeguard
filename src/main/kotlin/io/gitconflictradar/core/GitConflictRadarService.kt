package io.gitconflictradar.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ide.projectView.ProjectView
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.util.Alarm
import git4idea.repo.GitRepositoryManager
import io.gitconflictradar.model.FetchStatus
import io.gitconflictradar.model.ConflictWarning
import io.gitconflictradar.model.RepositorySnapshot
import io.gitconflictradar.settings.GitConflictRadarSettings
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.LocalFileSystem

/** Owns repository discovery and safe, read-only working-tree monitoring for one IDE project. */
@Service(Service.Level.PROJECT)
class GitConflictRadarService(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val snapshots = AtomicReference<List<RepositorySnapshot>>(emptyList())

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val hasChange = events.any { event ->
                        val file = event.file
                        file != null && !file.isDirectory && (hasWarning(file) || currentSnapshots().any { snap ->
                            try {
                                file.toNioPath().startsWith(snap.root)
                            } catch (_: Exception) {
                                false
                            }
                        })
                    }
                    if (hasChange && GitConflictRadarSettings.getInstance().state.autoRefreshOnSave) {
                        refreshAlarm.cancelAllRequests()
                        if (!project.isDisposed) {
                            refreshAlarm.addRequest({ refresh(fetch = false) }, 2000)
                        }
                    }
                }
            }
        )

        refresh(fetch = true)
    }

    fun currentSnapshots(): List<RepositorySnapshot> = snapshots.get()

    fun warningsFor(file: VirtualFile): List<ConflictWarning> {
        val projectRoot = project.basePath?.let { Path.of(it) } ?: return emptyList()
        return try {
            val relativePath = projectRoot.relativize(file.toNioPath()).toString().replace('\\', '/')
            snapshots.get().flatMap { snapshot ->
                snapshot.warnings.filter { it.filePath == relativePath }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun hasWarning(file: VirtualFile): Boolean = warningsFor(file).isNotEmpty()

    /** Refreshes repository metadata. Fetch only updates remote-tracking refs; it never touches the working tree. */
    fun refresh(fetch: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val previousSnapshots = currentSnapshots()
            val discovered = discoverRepositories().map { newlyDiscovered ->
                val prev = previousSnapshots.find { it.root == newlyDiscovered.root }
                if (prev != null) {
                    newlyDiscovered.copy(warnings = prev.warnings, fetchedAt = prev.fetchedAt)
                } else {
                    newlyDiscovered
                }
            }
            publish(discovered.map { it.copy(fetchStatus = FetchStatus.FETCHING) })

            val updated = if (fetch) {
                discovered.map { fetchRepository(it) }
            } else {
                discovered.map { it.copy(fetchStatus = FetchStatus.SUCCESS) }
            }
            val analysed = updated.map(::analysePotentialConflicts)
            publish(analysed)
            scheduleNextRefresh()
        }
    }

    private fun discoverRepositories(): List<RepositorySnapshot> = ReadAction.compute<List<RepositorySnapshot>, RuntimeException> {
        GitRepositoryManager.getInstance(project).repositories.map { repository ->
            RepositorySnapshot(
                root = repository.root.toNioPath(),
                branch = repository.currentBranch?.name,
                upstream = repository.branchTrackInfos
                    .find { it.localBranch == repository.currentBranch }
                    ?.remoteBranch
                    ?.name,
                remotes = repository.remotes.map { it.name },
                fetchedAt = null,
                fetchStatus = FetchStatus.NOT_FETCHED,
            )
        }
    }

    private fun fetchRepository(snapshot: RepositorySnapshot): RepositorySnapshot {
        return try {
            val process = ProcessBuilder("git", "fetch", "--quiet")
                .directory(snapshot.root.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val completed = process.waitFor() == 0
            snapshot.copy(
                fetchedAt = Instant.now(),
                fetchStatus = if (completed) FetchStatus.SUCCESS else FetchStatus.FAILED,
                message = output.ifBlank { if (completed) null else "git fetch failed" },
            )
        } catch (error: Exception) {
            thisLogger().warn("GitConflictRadar could not fetch ${snapshot.root}", error)
            snapshot.copy(fetchedAt = Instant.now(), fetchStatus = FetchStatus.FAILED, message = error.message)
        }
    }

    /**
     * Finds files changed on both sides of the merge base. This is intentionally conservative:
     * a shared changed file is a high-risk warning, not a claim that Git will certainly conflict.
     */
    private fun analysePotentialConflicts(snapshot: RepositorySnapshot): RepositorySnapshot {
        if (snapshot.branch == null) return snapshot
        return try {
            val allCandidates = git(snapshot.root, "for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes")
                ?.lineSequence()
                ?.map(String::trim)
                ?.filter { it.isNotBlank() && !it.endsWith("/HEAD") }
                ?.filter { it != snapshot.branch && it != snapshot.upstream }
                ?.distinct()
                // A pushed local branch and its origin/<branch> ref usually point to the same commit.
                ?.distinctBy { ref -> git(snapshot.root, "rev-parse", ref)?.trim() ?: ref }
                ?.take(GitConflictRadarSettings.getInstance().state.maxBranchesToAnalyze)
                ?.toList()
                .orEmpty()

            // Find parent and parallel branches from same parent
            val mergeBases = allCandidates.associateWith { branch ->
                git(snapshot.root, "merge-base", "HEAD", branch)?.trim().orEmpty()
            }.filterValues { it.isNotBlank() }

            var parentCommit: String? = null
            for (commit in mergeBases.values) {
                if (parentCommit == null) {
                    parentCommit = commit
                } else if (parentCommit != commit) {
                    val isAncestor = git(snapshot.root, "merge-base", "--is-ancestor", parentCommit, commit) != null
                    if (isAncestor) {
                        parentCommit = commit
                    }
                }
            }

            val candidates = if (parentCommit != null) {
                mergeBases.filter { (_, commit) ->
                    commit == parentCommit || git(snapshot.root, "merge-base", "--is-ancestor", commit, parentCommit) != null
                }.keys.toList()
            } else {
                emptyList()
            }

            val targetBranches = mergeBases.filterValues { it == parentCommit }.keys
            
            // Discover active PR commits from remote
            val prCommits = git(snapshot.root, "ls-remote", "origin", "refs/pull/*/head", "refs/merge-requests/*/head")
                ?.lineSequence()
                ?.map { it.substringBefore('\t').trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()

            val warnings = candidates.flatMap { candidate -> 
                val candidateCommit = git(snapshot.root, "rev-parse", candidate)?.trim()
                val isTarget = targetBranches.contains(candidate)
                val isPr = candidateCommit != null && prCommits.contains(candidateCommit)
                warningsForBranch(snapshot.root, candidate, isTarget || isPr) 
            }
                .distinctBy { "${it.filePath}|${it.conflictingBranch}" }
                .sortedWith(compareBy(ConflictWarning::filePath, ConflictWarning::conflictingBranch))
            snapshot.copy(warnings = warnings)
        } catch (error: Exception) {
            thisLogger().warn("GitConflictRadar could not analyse ${snapshot.root}", error)
            snapshot
        }
    }

    private fun warningsForBranch(root: Path, candidate: String, isTargetOrPr: Boolean): List<ConflictWarning> {
        val mergeBase = git(root, "merge-base", "HEAD", candidate)?.trim().orEmpty()
        if (mergeBase.isBlank()) return emptyList()
        val localFiles = changedFiles(root, "$mergeBase..HEAD") + workingTreeFiles(root)
        val candidateFiles = changedFiles(root, "$mergeBase..$candidate")
        val sharedFiles = localFiles.intersect(candidateFiles)
        if (sharedFiles.isEmpty()) return emptyList()

        val details = git(root, "log", "-1", "--format=%h%x09%an", candidate)
            ?.trim()
            ?.split('\t', limit = 2)
            .orEmpty()
        val projectRoot = project.basePath?.let { Path.of(it) }
        return sharedFiles.map { file ->
            val absoluteFilePath = root.resolve(file)
            val relativeToProject = if (projectRoot != null) {
                try {
                    projectRoot.relativize(absoluteFilePath).toString().replace('\\', '/')
                } catch (_: Exception) {
                    file
                }
            } else {
                file
            }
            
            val remoteDiffText = git(root, "diff", "--unified=0", "$mergeBase..$candidate", "--", file)
                ?.trim()
                ?.take(2_000)
            val localDiffText = git(root, "diff", "--unified=0", "$mergeBase..HEAD", "--", file)
                ?.trim()
                ?.take(2_000)
            
            val localRanges = parseDiffOldRanges(localDiffText)
            val remoteRanges = parseDiffOldRanges(remoteDiffText)
            val hasOverlap = localRanges.any { lr ->
                remoteRanges.any { rr ->
                    lr.first <= rr.last + 1 && rr.first <= lr.last + 1
                }
            }
            val hasRealConflict = hasOverlap && isTargetOrPr

            ConflictWarning(
                filePath = relativeToProject,
                conflictingBranch = candidate,
                commit = details.getOrNull(0),
                author = details.getOrNull(1),
                remoteDiff = remoteDiffText,
                mergeBase = mergeBase,
                isRealConflict = hasRealConflict,
            )
        }
    }

    private fun parseDiffOldRanges(diffOutput: String?): List<IntRange> {
        if (diffOutput == null) return emptyList()
        val ranges = mutableListOf<IntRange>()
        for (line in diffOutput.lines()) {
            if (line.startsWith("@@")) {
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    val oldRangeStr = parts[1].removePrefix("-")
                    val rangeParts = oldRangeStr.split(",")
                    val start = rangeParts[0].toIntOrNull()
                    if (start != null) {
                        val count = rangeParts.getOrNull(1)?.toIntOrNull() ?: 1
                        val end = (start + count - 1).coerceAtLeast(start)
                        ranges.add(start..end)
                    }
                }
            }
        }
        return ranges
    }

    private fun changedFiles(root: Path, revisionRange: String): Set<String> =
        git(root, "diff", "--name-only", "--diff-filter=ACMRT", revisionRange)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()

    /** Includes staged, unstaged, and untracked files so warnings appear before a developer commits. */
    private fun workingTreeFiles(root: Path): Set<String> = sequenceOf(
        git(root, "diff", "--name-only"),
        git(root, "diff", "--cached", "--name-only"),
        git(root, "ls-files", "--others", "--exclude-standard"),
    ).flatMap { output ->
        output.orEmpty().lineSequence().map(String::trim).filter(String::isNotBlank)
    }.toSet()

    fun git(root: Path, vararg arguments: String): String? = try {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) output else null
    } catch (_: Exception) {
        null
    }

    private fun scheduleNextRefresh() {
        alarm.cancelAllRequests()
        val settings = GitConflictRadarSettings.getInstance().state
        if (!settings.backgroundFetchEnabled || project.isDisposed) return
        alarm.addRequest({ refresh(fetch = true) }, settings.fetchIntervalMinutes.coerceIn(1, 120) * 60_000)
    }

    private val knownWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private var isInitialized = false

    private fun showNewConflictNotification(root: Path, warning: ConflictWarning) {
        val branch = warning.conflictingBranch
        val file = warning.filePath.substringAfterLast('/')

        val notification = com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("GitConflictRadar Notifications")
            .createNotification(
                "New Conflict Warning",
                "Potential merge conflict detected in $file from branch '$branch'.",
                com.intellij.notification.NotificationType.WARNING
            )

        val showDiffAction = object : com.intellij.openapi.actionSystem.AnAction("Show Diff") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                showDiffForWarning(root, warning)
            }
        }
        notification.addAction(showDiffAction)
        notification.notify(project)
    }

    fun showDiffForWarning(root: Path, warning: ConflictWarning) {
        val branch = warning.conflictingBranch
        val relativePath = warning.filePath
        val projectRoot = project.basePath?.let { Path.of(it) } ?: root
        val absolutePath = projectRoot.resolve(relativePath)
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(absolutePath) ?: return
        val repoRelativePath = root.relativize(absolutePath).toString().replace('\\', '/')

        ApplicationManager.getApplication().executeOnPooledThread {
            val baseContentText = git(root, "show", "${warning.mergeBase}:$repoRelativePath") ?: ""
            val branchContentText = git(root, "show", "$branch:$repoRelativePath") ?: ""

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val factory = com.intellij.diff.DiffContentFactory.getInstance()
                val fileType = virtualFile.fileType
                val baseContent = factory.create(project, baseContentText, fileType)
                val branchContent = factory.create(project, branchContentText, fileType)
                val request = com.intellij.diff.requests.SimpleDiffRequest(
                    "Conflict Branch Changes for ${virtualFile.name}",
                    baseContent,
                    branchContent,
                    "Common Ancestor (${warning.mergeBase})",
                    "Conflict Branch ($branch)"
                )
                request.putUserData(io.gitconflictradar.ui.GIT_CONFLICT_RADAR_VIRTUAL_FILE, virtualFile)
                com.intellij.diff.DiffManager.getInstance().showDiff(project, request)
            }
        }
    }

    private fun publish(next: List<RepositorySnapshot>) {
        snapshots.set(next)

        val isFinal = next.none { it.fetchStatus == FetchStatus.FETCHING }
        if (isFinal) {
            val currentWarnings = next.flatMap { snap ->
                snap.warnings.map { it.filePath to it.conflictingBranch }
            }.toSet()

            if (!isInitialized) {
                knownWarnings.addAll(currentWarnings)
                isInitialized = true
            } else {
                val newWarnings = next.flatMap { snap ->
                    snap.warnings.map { snap to it }
                }.filter { (_, warning) ->
                    (warning.filePath to warning.conflictingBranch) !in knownWarnings
                }

                for ((snap, warning) in newWarnings) {
                    showNewConflictNotification(snap.root, warning)
                }

                knownWarnings.clear()
                knownWarnings.addAll(currentWarnings)
            }
        }

        project.messageBus.syncPublisher(GitConflictRadarTopics.SNAPSHOTS_CHANGED).snapshotsUpdated(next)
        project.service<io.gitconflictradar.ui.GitConflictRadarInlayManager>().refreshInlays()
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                ProjectView.getInstance(project).refresh()
                DaemonCodeAnalyzer.getInstance(project).restart()
                try {
                    val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("GitConflictRadar")
                    if (toolWindow != null) {
                        val warningCount = next.sumOf { it.warnings.size }
                        val hasReal = next.any { snap -> snap.warnings.any { it.isRealConflict } }
                        toolWindow.setIcon(io.gitconflictradar.ui.createNumberIcon(warningCount, hasReal))
                        com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)?.repaint()
                    }
                } catch (e: Throwable) {
                    // Prevent any crash
                }
            }
        }
    }

    override fun dispose() = Unit
}
