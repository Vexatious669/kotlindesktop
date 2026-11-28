package me.enivzoy.anilife.app.data

import me.enivzoy.anilife.core.model.AnimeCard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.util.UUID

enum class DownloadStatus { Queued, Running, Done, Failed, Cancelled }

// lets tests drive the queue without network or files
fun interface DownloadRunner {
    suspend fun run(
        job: DownloadJob,
        url: String,
        headers: Map<String, String>,
        onProgress: (Long, Long?) -> Unit,
    ): LocalEpisode
}

data class DownloadJob(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val episode: Double,
    val url: String,
    val audio: String,
    val downloadRoot: Path?,
    val anime: AnimeCard? = null,
    val episodeTitle: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxHeight: Int = Int.MAX_VALUE,
    val resolve: (suspend (avoidUrl: String?) -> AnimeVideoSource?)? = null,
    val status: DownloadStatus = DownloadStatus.Queued,
    val downloaded: Long = 0L,
    val total: Long? = null,
    val error: String? = null,
    val localEpisode: LocalEpisode? = null,
)

class DownloadQueueManager(
    private val repository: AniListRepository?,
    private val scope: CoroutineScope,
    private val concurrency: Int = 3,
    private val runner: DownloadRunner? = null,
) {
    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val semaphore = Semaphore(concurrency.coerceAtLeast(1))
    private val handles = mutableMapOf<String, Job>()

    fun enqueue(
        title: String,
        episode: Double,
        url: String,
        audio: String,
        downloadRoot: Path?,
        anime: AnimeCard? = null,
        episodeTitle: String? = null,
        headers: Map<String, String> = emptyMap(),
        maxHeight: Int = Int.MAX_VALUE,
        resolve: (suspend (avoidUrl: String?) -> AnimeVideoSource?)? = null,
    ): String {
        val job = DownloadJob(
            title = title,
            episode = episode,
            url = url,
            audio = audio,
            downloadRoot = downloadRoot,
            anime = anime,
            episodeTitle = episodeTitle,
            headers = headers,
            maxHeight = maxHeight,
            resolve = resolve,
        )
        _jobs.update { it + job }
        launchJob(job)
        return job.id
    }

    private fun launchJob(job: DownloadJob) {
        val handle = scope.launch {
            semaphore.withPermit {
                _jobs.update { list ->
                    list.map {
                        if (it.id == job.id) {
                            it.copy(status = DownloadStatus.Running, downloaded = 0L, total = null, error = null)
                        } else it
                    }
                }
                try {
                    val result = download(job)
                    _jobs.update { list ->
                        list.map {
                            if (it.id == job.id) it.copy(
                                status = DownloadStatus.Done,
                                localEpisode = result,
                                downloaded = result.bytes,
                                total = result.bytes,
                            ) else it
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _jobs.update { list -> list.map { if (it.id == job.id) it.copy(status = DownloadStatus.Cancelled) else it } }
                    throw e
                } catch (t: Throwable) {
                    _jobs.update { list ->
                        list.map {
                            if (it.id == job.id) it.copy(
                                status = DownloadStatus.Failed,
                                error = t.message?.takeIf(String::isNotBlank) ?: "The video download failed.",
                            ) else it
                        }
                    }
                }
            }
        }
        handles[job.id] = handle
    }

    // sources are signed and expire, so every attempt resolves again instead of reusing the queued url
    private suspend fun download(job: DownloadJob): LocalEpisode {
        val resolve = job.resolve
        var source = resolve?.let { runCatching { it(null) }.getOrNull() }
        var attempt = 0
        while (true) {
            attempt++
            val url = source?.url ?: job.url
            val headers = source?.headers ?: job.headers
            try {
                val progress: (Long, Long?) -> Unit = { downloaded, total ->
                    _jobs.update { list ->
                        list.map { if (it.id == job.id) it.copy(downloaded = downloaded, total = total) else it }
                    }
                }
                if (runner != null) {
                    return runner.run(job, url, headers, progress)
                }
                val downloader = requireNotNull(repository) { "No download runner is configured." }
                return downloader.downloadDirect(
                    url = url,
                    animeTitle = job.title,
                    episode = job.episode,
                    audio = job.audio,
                    downloadRoot = job.downloadRoot,
                    headers = headers,
                    maxHeight = job.maxHeight,
                    onProgress = progress,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val retryable = failure.sourceException()?.reason?.retryWithFreshSource == true
                if (resolve == null || attempt >= 2 || !retryable) throw failure
                val fresh = runCatching { resolve(url) }.getOrNull() ?: throw failure
                source = fresh
                _jobs.update { list ->
                    list.map {
                        if (it.id == job.id) {
                            it.copy(
                                url = fresh.url,
                                headers = fresh.headers,
                                downloaded = 0L,
                                total = null,
                            )
                        } else it
                    }
                }
            }
        }
    }

    fun cancel(id: String) {
        handles[id]?.cancel()
        _jobs.update { list ->
            list.map {
                if (it.id == id && (it.status == DownloadStatus.Queued || it.status == DownloadStatus.Running)) {
                    it.copy(status = DownloadStatus.Cancelled)
                } else it
            }
        }
    }

    fun retry(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.status != DownloadStatus.Failed && job.status != DownloadStatus.Cancelled) return
        _jobs.update { list ->
            list.map {
                if (it.id == id) it.copy(status = DownloadStatus.Queued, downloaded = 0L, total = null, error = null) else it
            }
        }
        val fresh = _jobs.value.first { it.id == id }
        launchJob(fresh)
    }

    fun remove(id: String) {
        cancel(id)
        _jobs.update { list -> list.filter { it.id != id } }
        handles.remove(id)
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.status != DownloadStatus.Done && it.status != DownloadStatus.Failed && it.status != DownloadStatus.Cancelled } }
    }
}
