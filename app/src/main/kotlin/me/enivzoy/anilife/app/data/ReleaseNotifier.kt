package me.enivzoy.anilife.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.enivzoy.anilife.core.model.AnimeCard

class ReleaseNotifier(
    private val repository: AniListRepository,
    private val store: NoticeStore = NoticeStoreInstance,
) {
    private val mutableLastCheck = MutableStateFlow(0L)
    val lastCheck: StateFlow<Long> = mutableLastCheck.asStateFlow()

    suspend fun check(): Int {
        val library = repository.library.entries.value

        if (library.isEmpty()) {
            mutableLastCheck.value = System.currentTimeMillis()
            return 0
        }

        val airing = try {
            repository.recentlyAired(AIRING_LOOKBACK_HOURS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            System.err.println(
                "ReleaseNotifier: failed to fetch recently aired episodes: " +
                    "${failure::class.simpleName}: ${failure.message}"
            )
            failure.printStackTrace()
            emptyList()
        }

        val nowSeconds = System.currentTimeMillis() / 1000

        val byId = airing
            .filter { it.airingAt <= nowSeconds }
            .associateBy { it.anime.id }

        var added = 0

        library.forEach { anime ->
            val entry = byId[anime.id] ?: return@forEach
            val episode = entry.episode

            if (store.hasAnnounced(anime.id, episode)) {
                return@forEach
            }

            store.add(
                kind = "airing",
                title = anime.title,
                body = "Episode $episode is out",
                animeId = anime.id,
                image = anime.cover,
            )

            added++
        }

        mutableLastCheck.value = System.currentTimeMillis()
        return added
    }

    fun announceDownload(anime: AnimeCard, episodes: Int) {
        store.add(
            kind = "download",
            title = anime.title,
            body = if (episodes == 1) {
                "Downloaded 1 episode"
            } else {
                "Downloaded $episodes episodes"
            },
            animeId = anime.id,
            image = anime.cover,
        )
    }

    companion object {
        const val AIRING_LOOKBACK_HOURS = 48
    }
}
