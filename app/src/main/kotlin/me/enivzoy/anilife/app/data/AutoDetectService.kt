package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.enivzoy.anilife.core.model.AnimeCard
import java.nio.file.Files
import java.nio.file.Path

// the detection feature only needs these capabilities from the repository
internal interface AutoDetectLibrary {
    suspend fun searchAniList(query: String): List<AnimeCard>
    suspend fun lookupAniList(ids: List<Long>): List<AnimeCard> = emptyList()
    fun register(episodes: List<LocalEpisode>, anime: List<AnimeCard>)
}

internal fun AniListRepository.asAutoDetectLibrary(): AutoDetectLibrary = object : AutoDetectLibrary {
    override suspend fun searchAniList(query: String): List<AnimeCard> = search(query).results

    override suspend fun lookupAniList(ids: List<Long>): List<AnimeCard> =
        lookupCards(ids, failFast = false).cards

    override fun register(episodes: List<LocalEpisode>, anime: List<AnimeCard>) {
        addLocalEpisodes(episodes)
        addToLibrary(anime)
    }
}

internal data class AutoDetectEpisode(
    val path: Path,
    val episode: Double,
    val bytes: Long,
)

internal data class AutoDetectCandidate(
    val folderName: String,
    val path: Path,
    val title: FolderTitle,
    val matches: List<TitleMatch>,
    val episodes: List<AutoDetectEpisode>,
    val totalBytes: Long,
    val newestModifiedAt: Long,
    val settled: Boolean,
    val savedHint: SavedLibHint?,
) {
    val bestMatch: TitleMatch? get() = matches.firstOrNull()
    val needsConfirmation: Boolean get() {
        val best = bestMatch ?: return true
        if (best.confidence == MatchConfidence.Weak) return true
        if (best.confidence == MatchConfidence.Ambiguous) return true
        val runnerUp = matches.getOrNull(1) ?: return false
        return best.score - runnerUp.score < 0.08
    }
}

internal data class AutoDetectScan(
    val scannedAt: Long,
    val folders: List<AutoDetectCandidate>,
    val skipped: List<String>,
    val watched: List<String>,
    val ignored: List<String> = emptyList(),
) {
    val detected: List<AutoDetectCandidate> get() = folders.filter { it.settled }
    val pending: List<AutoDetectCandidate> get() = folders.filterNot { it.settled }
    val needsConfirmation: List<AutoDetectCandidate> get() = detected.filter { it.needsConfirmation }
    val ready: List<AutoDetectCandidate> get() = detected.filterNot { it.needsConfirmation }
}

internal data class AutoDetectOutcome(val importedEpisodes: Int, val importedAnime: Int)

// polls instead of registering a recursive fs watcher: one thread, no handle exhaustion, works on every drive type
internal class AutoDetectService(
    private val library: AutoDetectLibrary,
    private val store: AutoDetectStore = AutoDetectStore(),
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMillis: Long = 15_000L,
    private val searchSpacingMillis: Long = 750L,
    private val maxFoldersPerScan: Int = 25,
) {
    private val _scan = MutableStateFlow<AutoDetectScan?>(null)
    val scan: StateFlow<AutoDetectScan?> = _scan.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val scanLock = Mutex()
    private var job: Job? = null
    // a folder that waits for the user must not be re-searched on every poll
    private val resolved = mutableMapOf<String, Pair<String, AutoDetectCandidate>>()

    private var directories: List<Path> = emptyList()
    private var settleSeconds: Int = 45
    private var audio: String = "sub"
    private var autoImport: Boolean = true

    fun configure(
        directories: List<String>,
        settleSeconds: Int,
        audio: String,
        autoImport: Boolean,
    ) {
        this.directories = directories
            .mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
            .distinct()
        this.settleSeconds = settleSeconds.coerceIn(10, 600)
        this.audio = if (audio == "dub") "dub" else "sub"
        this.autoImport = autoImport
    }

    fun start() {
        // check folders before the job: dropping the last folder while running must stop the poller
        if (directories.isEmpty()) {
            job?.cancel()
            job = null
            _running.value = false
            _status.value = "Add at least one download folder to watch."
            return
        }
        if (job?.isActive == true) return
        _running.value = true
        _status.value = "Watching ${directories.size} folder${if (directories.size == 1) "" else "s"}."
        job = scope.launch {
            while (isActive) {
                runCatching { scanOnce() }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        _status.value = "Scan failed: ${failure.message.orEmpty().take(160)}"
                    }
                delay(pollIntervalMillis)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _running.value = false
        _status.value = null
    }

    suspend fun scanOnce(): AutoDetectScan = scanLock.withLock {
        val roots = directories
        if (roots.isEmpty()) {
            val empty = AutoDetectScan(clock(), emptyList(), emptyList(), emptyList())
            _scan.value = empty
            return@withLock empty
        }
        val skipped = mutableListOf<String>()
        val candidates = mutableListOf<AutoDetectCandidate>()
        val seen = mutableSetOf<String>()
        var searches = 0

        withContext(Dispatchers.IO) {
            // drop records for folders that were renamed or deleted, otherwise they pin stale state forever
            store.imported.keys.toList().forEach { key ->
                val path = runCatching { Path.of(key) }.getOrNull()
                if (path == null || !Files.exists(path)) store.forget(key)
            }
            roots.forEach { root ->
                val hints = AutoDetectMatcher.readSavedLib(root)
                findAnimeFolders(root, skipped).forEach { folder ->
                    val key = keyOf(folder)
                    if (key in store.ignored) return@forEach
                    if (!seen.add(key)) return@forEach
                    val candidate = describe(folder, skipped, hints) ?: return@forEach
                    if (!candidate.settled) {
                        candidates += candidate
                        return@forEach
                    }
                    val imported = store.imported[key]
                    if (imported != null && imported.matches(candidate)) return@forEach
                    val signature = signatureOf(candidate)
                    val cached = resolved[key]
                    if (cached != null && cached.first == signature) {
                        candidates += candidate.copy(matches = cached.second.matches)
                        return@forEach
                    }
                    if (searches >= maxFoldersPerScan) {
                        skipped += "${candidate.folderName}: waiting for the next scan"
                        return@forEach
                    }
                    searches++
                    val hinted = resolveMatches(candidate, skipped, imported?.animeId)
                    if (hinted.matches.isNotEmpty()) resolved[key] = signature to hinted
                    candidates += hinted
                    if (searches < maxFoldersPerScan) delay(searchSpacingMillis)
                }
            }
            resolved.keys.retainAll(seen)
        }

        val result = AutoDetectScan(
            scannedAt = clock(),
            folders = candidates.sortedWith(compareBy({ it.folderName.lowercase() })),
            skipped = skipped,
            watched = roots.map { it.toString() },
            ignored = store.ignored.sorted(),
        )
        _scan.value = result
        if (autoImport) importReady(result.folders)
        val summary = buildList {
            if (result.ready.isNotEmpty()) add("${result.ready.size} ready")
            if (result.needsConfirmation.isNotEmpty()) add("${result.needsConfirmation.size} to confirm")
            if (result.pending.isNotEmpty()) add("${result.pending.size} downloading")
        }
        _status.value = summary.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: "Watching ${roots.size} folder${if (roots.size == 1) "" else "s"}."
        result
    }

    // ambiguous folders always wait for the user, so an unattended scan never guesses wrong
    suspend fun importReady(folders: List<AutoDetectCandidate> = scan.value?.folders.orEmpty()): AutoDetectOutcome =
        importCandidates(folders.filter { it.settled && !it.needsConfirmation && it.bestMatch != null })

    suspend fun importChosen(folder: AutoDetectCandidate, matchIndex: Int): AutoDetectOutcome {
        // an unfinished download would register half a file, so only settled folders import
        if (!folder.settled) return AutoDetectOutcome(0, 0)
        val chosen = folder.matches.getOrNull(matchIndex) ?: return AutoDetectOutcome(0, 0)
        return importCandidates(listOf(folder), listOf(chosen))
    }

    private suspend fun importCandidates(
        folders: List<AutoDetectCandidate>,
        picks: List<TitleMatch>? = null,
    ): AutoDetectOutcome {
        if (folders.isEmpty()) return AutoDetectOutcome(0, 0)
        val episodes = mutableListOf<LocalEpisode>()
        val anime = mutableListOf<AnimeCard>()
        folders.forEachIndexed { index, folder ->
            val match = picks?.getOrNull(index) ?: folder.bestMatch ?: return@forEachIndexed
            folder.episodes.forEach { episode ->
                episodes += LocalEpisode(
                    anime = match.anime,
                    episode = episode.episode,
                    audio = audio,
                    path = episode.path,
                    bytes = episode.bytes,
                )
            }
            anime += match.anime
        }
        if (episodes.isEmpty()) return AutoDetectOutcome(0, 0)
        library.register(episodes.distinctBy { entryKey(it) }, anime.distinctBy { it.id })
        folders.forEachIndexed { index, folder ->
            val match = picks?.getOrNull(index) ?: folder.bestMatch
            store.remember(
                AutoDetectEntry(
                    path = folder.path.toString(),
                    animeId = match?.anime?.id,
                    title = match?.anime?.title,
                    episodes = folder.episodes.size,
                    bytes = folder.totalBytes,
                    files = folder.episodes.size,
                    updatedAt = folder.newestModifiedAt,
                ),
            )
        }
        val current = _scan.value
        if (current != null) {
            val remaining = current.folders.filterNot { updated -> folders.any { it.path == updated.path } }
            _scan.value = current.copy(folders = remaining)
        }
        return AutoDetectOutcome(episodes.size, anime.distinctBy { it.id }.size)
    }

    fun ignore(path: Path) {
        val key = keyOf(path)
        store.ignore(key)
        val current = _scan.value ?: return
        _scan.value = current.copy(
            folders = current.folders.filterNot { keyOf(it.path) == key },
            ignored = (current.ignored + key).sorted(),
        )
    }

    fun unignore(path: String) {
        store.unignore(path)
        val current = _scan.value ?: return
        _scan.value = current.copy(ignored = current.ignored.filterNot { it == path })
    }

    private suspend fun resolveMatches(
        candidate: AutoDetectCandidate,
        skipped: MutableList<String>,
        knownAnimeId: Long? = null,
    ): AutoDetectCandidate {
        // a saved.lib or a previous import names the anime, so resolve it without another search
        val knownId = candidate.savedHint?.animeId ?: knownAnimeId
        if (knownId != null) {
            val known = runCatching { library.lookupAniList(listOf(knownId)) }.getOrNull()?.firstOrNull()
            if (known != null && AutoDetectMatcher.matchScore(candidate.title, known) >= AutoDetectMatcher.AMBIGUOUS_SCORE) {
                return candidate.copy(matches = listOf(TitleMatch(known, 1.0, MatchConfidence.Exact)))
            }
        }
        val query = AutoDetectMatcher.searchQuery(candidate.title)
        val results = try {
            library.searchAniList(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            skipped += "${candidate.folderName}: AniList unavailable (${failure.message.orEmpty().take(80)})"
            return candidate.copy(matches = emptyList())
        }
        val ranked = AutoDetectMatcher.rank(candidate.title, results)
        if (ranked.isEmpty()) {
            skipped += "${candidate.folderName}: no AniList match for \"$query\""
            return candidate.copy(matches = emptyList())
        }
        val hint = candidate.savedHint?.animeId ?: knownAnimeId
        val ordered = if (hint != null) {
            val known = ranked.firstOrNull { it.anime.id == hint }
            if (known == null) ranked else listOf(known.copy(confidence = MatchConfidence.Exact)) + ranked.filterNot { it.anime.id == hint }
        } else {
            ranked
        }
        return candidate.copy(matches = ordered)
    }

    private fun signatureOf(candidate: AutoDetectCandidate): String =
        "${candidate.episodes.size}|${candidate.totalBytes}|${candidate.newestModifiedAt}"

    private fun describe(
        folder: Path,
        skipped: MutableList<String>,
        hints: List<SavedLibHint>,
    ): AutoDetectCandidate? {
        val files = mediaFiles(folder)
        if (files.isEmpty()) {
            skipped += "${folder.fileName}: no video files"
            return null
        }
        val episodes = mutableListOf<AutoDetectEpisode>()
        var newest = 0L
        var total = 0L
        files.forEach { file ->
            val size = runCatching { Files.size(file) }.getOrDefault(0L)
            val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
            newest = maxOf(newest, modified)
            total += size
            val number = MediaAutoDetector.episodeNumber(file.fileName.toString())
            if (number == null) {
                skipped += "${folder.fileName}/${file.fileName}: episode number not found"
            } else {
                episodes += AutoDetectEpisode(file, number, size)
            }
        }
        if (episodes.isEmpty()) {
            skipped += "${folder.fileName}: no episode numbers"
            return null
        }
        val name = displayName(folder)
        val title = AutoDetectMatcher.parseFolder(name)
        val settled = isSettled(folder, newest)
        return AutoDetectCandidate(
            folderName = name,
            path = folder,
            title = title,
            matches = emptyList(),
            episodes = episodes.sortedBy { it.episode },
            totalBytes = total,
            newestModifiedAt = newest,
            settled = settled,
            savedHint = AutoDetectMatcher.matchHint(title, hints),
        )
    }

    private fun isSettled(folder: Path, newest: Long): Boolean {
        if (hasTemporaryFiles(folder)) return false
        if (newest == 0L) return false
        return clock() - newest >= settleSeconds * 1000L
    }

    private fun hasTemporaryFiles(folder: Path): Boolean = runCatching {
        Files.walk(folder, MAX_DEPTH).use { stream ->
            stream.anyMatch { path ->
                Files.isRegularFile(path) && TEMP_EXTENSIONS.any { path.fileName.toString().endsWith(it, ignoreCase = true) }
            }
        }
    }.getOrDefault(false)

    // only direct episode files mark a folder as the series, so a container with season subfolders still descends
    private fun hasDirectMedia(directory: Path): Boolean = runCatching {
        Files.list(directory).use { stream ->
            stream.anyMatch { path ->
                Files.isRegularFile(path) && VIDEO_EXTENSIONS.any { path.fileName.toString().endsWith(it, ignoreCase = true) }
            }
        }
    }.getOrDefault(false)

    private fun mediaFiles(folder: Path): List<Path> = runCatching {
        Files.walk(folder, MAX_DEPTH).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { path -> VIDEO_EXTENSIONS.any { path.fileName.toString().endsWith(it, ignoreCase = true) } }
                .limit(MAX_FILES.toLong())
                .sorted()
                .toList()
        }
    }.getOrDefault(emptyList())

    // walks down through plain container folders such as Downloads/Anime until real episodes show up
    private fun findAnimeFolders(root: Path, skipped: MutableList<String>): List<Path> {
        val found = mutableListOf<Path>()
        fun descend(directory: Path, depth: Int) {
            if (depth > MAX_CONTAINER_DEPTH) return
            val children: List<Path> = runCatching {
                Files.list(directory).use { stream -> stream.sorted().toList() }
            }.getOrElse {
                skipped += "${directory.fileName}: unreadable"
                return
            }
            if (hasDirectMedia(directory)) {
                found.add(directory)
                return
            }
            children.filter { Files.isDirectory(it) }.forEach { descend(it, depth + 1) }
        }
        descend(root, 0)
        return found
    }

    // "Show/Season 2/*.mkv" must be recognised as Show, not Season 2
    private fun displayName(folder: Path): String {
        var name = folder.fileName?.toString().orEmpty()
        var parent = folder.parent
        while (parent != null && SEASON_FOLDER.containsMatchIn(name)) {
            val candidate = parent.fileName?.toString().orEmpty()
            if (candidate.isBlank() || directories.any { it == parent }) break
            name = candidate
            parent = parent.parent
        }
        return name
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf(".mkv", ".mp4", ".m4v", ".webm", ".avi", ".mov", ".ts")
        val TEMP_EXTENSIONS = setOf(
            ".part", ".partial", ".tmp", ".crdownload", ".download", ".downloading",
            ".aria2", ".!qb", ".bts", ".opdownload", ".td", ".st", ".dl",
        )
        val SEASON_FOLDER = Regex("^(season|series)\\s*\\d+$|^s\\d{1,2}$|^(specials|extras|bonus|ovas?)$", RegexOption.IGNORE_CASE)
        const val MAX_DEPTH = 3
        const val MAX_CONTAINER_DEPTH = 4
        const val MAX_FILES = 2000
    }
}

private fun AutoDetectEntry.matches(candidate: AutoDetectCandidate): Boolean =
    files == candidate.episodes.size &&
        bytes == candidate.totalBytes &&
        (updatedAt == candidate.newestModifiedAt || updatedAt == 0L)

// store.remember writes path.toString(), so both sides must agree on the same key
internal fun keyOf(folder: Path): String = folder.toAbsolutePath().normalize().toString()
