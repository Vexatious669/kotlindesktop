package me.enivzoy.anilife.app.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import xyz.orbitary.perigee.ConnectionState
import xyz.orbitary.perigee.PerigeeClient
import xyz.orbitary.perigee.activity.Activity
import xyz.orbitary.perigee.activity.ActivityType
import xyz.orbitary.perigee.activity.StatusDisplayType
import xyz.orbitary.perigee.activity.Timestamps
import xyz.orbitary.perigee.event.Disconnected
import xyz.orbitary.perigee.event.Ready
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** How often the worker wakes up to check its rate-limit and reconnect gates. */
private const val TICK_MS = 250L
/** Discord rejects SET_ACTIVITY bursts; never publish twice inside this window. */
private const val MIN_PUBLISH_INTERVAL = 5_000L
/** Backoff after a failed connect or two failed SET_ACTIVITY attempts. */
private const val RECONNECT_INTERVAL = 30_000L
/** Ignore position drift smaller than this; Discord's own timer handles it. */
private const val SEEK_TOLERANCE = 10.0
/** Republish even an unchanged card periodically so a Discord restart can't leave us blank. */
private const val REFRESH_INTERVAL = 5 * 60 * 1_000L
private const val IDLE_IDENTITY = "idle"

data class DiscordPresenceTarget(
    val sessionKey: String,
    val title: String,
    val isMovie: Boolean,
    val season: String?,
    val year: Int?,
    val episode: Double?,
    val seasonNumber: Int? = null,
    val cover: String? = null,
)

/** Metadata supplied by the player; playback state is added in [Presence]. */
data class DiscordPlaybackInfo(
    val animeId: Long,
    val title: String,
    val episode: Double,
    val episodeTitle: String? = null,
    val total: Int? = null,
    val cover: String? = null,
    val embedded: Boolean = false,
)

/** A full Rich Presence report. [identity] deliberately ignores the clock. */
data class Presence(
    val animeId: Long,
    val title: String,
    val episode: Double,
    val episodeTitle: String?,
    val total: Int?,
    val cover: String?,
    val position: Double,
    val duration: Double,
    val paused: Boolean,
    val embedded: Boolean = false,
) {
    fun identity(): String = "$animeId/$title/$episode/$episodeTitle/$paused"
}

sealed class PresenceMessage {
    data class Configure(
        val enabled: Boolean,
        val applicationId: String,
        val reconnect: Boolean = false,
    ) : PresenceMessage()

    data class Show(val presence: Presence, val reportedAt: Long) : PresenceMessage()
    data class Clear(val playersOnly: Boolean = false) : PresenceMessage()
    data class PublishResult(
        val generation: Long,
        val identity: String,
        val position: Double,
        val failure: Throwable?,
    ) : PresenceMessage()
}

enum class DiscordPresenceStatus {
    Disabled,
    Connecting,
    Connected,
    Unavailable,
}

data class DiscordPresenceState(
    val status: DiscordPresenceStatus = DiscordPresenceStatus.Disabled,
    /** The Discord account the desktop client is signed in as, once the pipe is ready. */
    val accountName: String? = null,
    val username: String? = null,
    val userId: String? = null,
    val avatarHash: String? = null,
) {
    val avatarUrl: String?
        get() = if (!userId.isNullOrBlank() && !avatarHash.isNullOrBlank()) {
            "https://cdn.discordapp.com/avatars/$userId/$avatarHash.png?size=256"
        } else {
            null
        }
}

/** Publicly hosted AniLife icon used as the presence image when nothing is playing. */
const val ANILIFE_ICON_URL =
    "https://raw.githubusercontent.com/anilifecommunity/kotlindesktop/main/app/src/main/resources/anilife-icon.png"

/** The idle card, kept for the Settings preview and for "nothing playing" reports. */
internal fun buildDiscordPresenceActivity(
    target: DiscordPresenceTarget?,
    startedAt: Instant,
): Activity {
    val details = target?.title?.trim()?.ifBlank { "Anime" } ?: "Idling"
    val state = target?.let { t ->
        val episode = t.episode
        if (episode != null && episode > 0) {
            val season = (t.seasonNumber ?: 1).coerceAtLeast(1)
            "On S%02dE%02d".format(season, episode.toInt())
        } else {
            "App developed by witherbl_de"
        }
    } ?: "App developed by witherbl_de"

    return Activity.builder()
        .name("AniLife")
        .type(ActivityType.WATCHING)
        // The member list says "Watching AniLife" or "Watching <series>" instead of the app name.
        .statusDisplay(StatusDisplayType.DETAILS)
        .details(details.discordText())
        .state(state.discordText())
        .apply {
            startedAt(startedAt)
            if (target != null) {
                target.cover?.takeIf(String::isNotBlank)?.let { cover ->
                    largeImage(cover.trim(), target.title.discordText())
                }
            } else {
                largeImage(ANILIFE_ICON_URL, "AniLife")
            }
        }
        .button("Visit AniLife", "https://anilife.cc")
        .button("Star on GitHub", "https://github.com/anilifecommunity/kotlindesktop")
        .build()
}

/** `Ep: 3/12`, with half-episodes kept as `10.5`. */
internal fun episodeLabel(episode: Double, total: Int?): String {
    val number = episode.episodeLabel()
    return if (total != null && total > 0) "Ep: $number/$total" else "Ep: $number"
}

/** Port of the Rust `activity()`: episode state, pause badge, cover and playback timestamps. */
internal fun buildActivity(p: Presence): Activity {
    val label = episodeLabel(p.episode, p.total)
    val state = p.episodeTitle?.trim()?.takeIf(String::isNotBlank)
        ?.let { "$label - $it" } ?: label

    val builder = Activity.builder()
        .name("AniLife")
        .type(ActivityType.WATCHING)
        .statusDisplay(StatusDisplayType.DETAILS)
        .details(p.title.trim().ifBlank { "Anime" }.discordText())
        .state(state.discordText())
        // Do not send unregistered asset keys ("icon"/"paused") here: Discord can reject the
        // entire SET_ACTIVITY when an app has no such assets. Pause state is already in `state`.
        .apply {
            p.cover?.trim()?.takeIf(String::isNotBlank)?.let { cover ->
                largeImage(cover, p.title.discordText())
            }
        }
        .button("Join the Anilili Discord", "https://discord.gg/Anilili")

    val now = Instant.now()
    if (!p.paused && p.duration > 0.0) {
        val start = now.minusMillis((p.position.coerceIn(0.0, p.duration) * 1000).toLong())
        val end = start.plusMillis((p.duration * 1000).toLong())
        builder.timestamps(Timestamps(start, end))
    } else if (!p.paused) {
        val start = now.minusMillis((p.position.coerceAtLeast(0.0) * 1000).toLong())
        builder.timestamps(Timestamps(start, null))
    }
    return builder.build()
}

/**
 * Playback-driven Rich Presence. A dedicated worker thread owns the IPC client and the
 * `wanted`/`published` state machine ported from the legacy implementation: reports are coalesced,
 * republished only when the card really changes (identity, >10s seek or a 5 minute refresh) and
 * retried automatically when Discord restarts.
 */
class DiscordPresenceManager : AutoCloseable {
    private data class Wanted(val presence: Presence, val reportedAt: Long)
    private data class Published(val identity: String, val position: Double, val at: Long)

    private val closed = AtomicBoolean(false)
    private val messages = Channel<PresenceMessage>(Channel.UNLIMITED)
    private val workerThread: Thread

    private val mutableState = MutableStateFlow(DiscordPresenceState())
    val state: StateFlow<DiscordPresenceState> = mutableState.asStateFlow()

    @Volatile private var enabled: Boolean = false
    @Volatile private var applicationId: String? = null
    @Volatile private var client: PerigeeClient? = null
    @Volatile private var connected: Boolean = false

    // Worker-owned fields: only touched from workerThread or the event callbacks it registers.
    private var wanted: Wanted? = null
    private var published: Published? = null
    private var nextAttempt: Long = 0L
    private var lastLoggedState: String = "none"
    private var generation: Long = 0L
    private var publishPending = false

    init {
        workerThread = Thread({ runWorker() }, "discord-rich-presence").apply { isDaemon = true }
        workerThread.start()
        DiscordPresenceLog.d("worker started; log file = ${DiscordPresenceLog.logFile()}")
    }

    /** Configure the pipe (and the settings guard). Reconnect forces a fresh IPC connection. */
    fun configure(enabled: Boolean, configuredApplicationId: String, reconnect: Boolean = false) {
        if (closed.get()) return
        val id = configuredApplicationId.trim()
        val active = enabled && id.isNotEmpty() && id.all(Char::isDigit)
        this.enabled = active
        this.applicationId = id.takeIf { active }
        DiscordPresenceLog.d(
            "configure enabled=$enabled id='$configuredApplicationId' active=$active reconnect=$reconnect " +
                "(note: OAuth2 sign-in is unrelated to the local Discord IPC pipe)",
        )
        send(PresenceMessage.Configure(active, id, reconnect))
    }

    /** Backwards-compatible entry point for callers that still have a [DiscordPresenceTarget]. */
    fun update(
        enabled: Boolean,
        configuredApplicationId: String,
        target: DiscordPresenceTarget?,
        reconnect: Boolean = false,
    ) {
        configure(enabled, configuredApplicationId, reconnect)
        if (target != null) {
            show(
                Presence(
                    animeId = target.sessionKey.toLongOrNull() ?: 0L,
                    title = target.title,
                    episode = target.episode?.takeIf { it > 0.0 } ?: 1.0,
                    episodeTitle = null,
                    total = null,
                    cover = target.cover,
                    position = 0.0,
                    duration = 0.0,
                    paused = false,
                ),
            )
        } else if (enabled) {
            send(PresenceMessage.Clear(playersOnly = true))
        }
    }

    /** Called by the player on every meaningful transport update. */
    fun show(presence: Presence) {
        if (closed.get() || !enabled) {
            DiscordPresenceLog.d(
                "show DROPPED (closed=${closed.get()} enabled=$enabled): ${presence.title} " +
                    "ep=${presence.episode} pos=${presence.position.toInt()} paused=${presence.paused}",
            )
            return
        }
        DiscordPresenceLog.d(
            "show: ${presence.title} ep=${presence.episode} pos=${presence.position.toInt()}/${presence.duration.toInt()} " +
                "paused=${presence.paused} identity=${presence.identity()}",
        )
        send(PresenceMessage.Show(presence, System.currentTimeMillis()))
    }

    /** Playback stopped: drop the player card and fall back to the idle card. */
    fun clearPlayer() {
        if (closed.get()) return
        DiscordPresenceLog.d("clearPlayer")
        send(PresenceMessage.Clear(playersOnly = true))
    }

    /** Presence disabled: clear the card and shut the pipe down. */
    fun clear() {
        if (closed.get()) return
        DiscordPresenceLog.d("clear (disable presence)")
        enabled = false
        applicationId = null
        send(PresenceMessage.Clear(playersOnly = false))
    }

    private fun send(message: PresenceMessage) {
        if (closed.get()) return
        messages.trySend(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        messages.close()
    }

    private fun runWorker() = runBlocking {
        while (!closed.get()) {
            // Drain bursts (mpv emits time-pos very often) so only the newest report matters.
            while (true) {
                val message = messages.tryReceive().getOrNull() ?: break
                handleMessage(message)
            }
            if (enabled) pump()
            delay(TICK_MS)
        }
        clearActivity()
        closeClient()
        publishState(DiscordPresenceState(DiscordPresenceStatus.Disabled))
    }

    private fun handleMessage(message: PresenceMessage) {
        when (message) {
            is PresenceMessage.Configure -> applyConfiguration(message)
            is PresenceMessage.Show -> {
                if (enabled) {
                    wanted = Wanted(message.presence, message.reportedAt)
                    DiscordPresenceLog.d("worker accepted Show -> wanted")
                } else {
                    DiscordPresenceLog.w("worker DROPPED Show (worker enabled=$enabled)")
                }
            }
            is PresenceMessage.PublishResult -> {
                if (message.generation != generation) {
                    DiscordPresenceLog.d("ignoring stale SET_ACTIVITY result (gen ${message.generation})")
                    return
                }
                publishPending = false
                val now = System.currentTimeMillis()
                if (message.failure == null) {
                    published = Published(message.identity, message.position, now)
                    nextAttempt = now + MIN_PUBLISH_INTERVAL
                    DiscordPresenceLog.d("SET_ACTIVITY accepted identity=${message.identity}")
                } else {
                    published = null
                    connected = false
                    // A slow Discord reply is not a broken pipe: retry after the normal window.
                    nextAttempt = now + if (message.failure is TimeoutException) {
                        MIN_PUBLISH_INTERVAL
                    } else {
                        RECONNECT_INTERVAL
                    }
                    DiscordPresenceLog.e(
                        "SET_ACTIVITY failed identity=${message.identity}: ${message.failure}",
                        message.failure,
                    )
                    if (mutableState.value.status == DiscordPresenceStatus.Connected) {
                        publishState(DiscordPresenceState(DiscordPresenceStatus.Unavailable))
                    }
                }
            }
            is PresenceMessage.Clear -> {
                wanted = null
                if (message.playersOnly) {
                    DiscordPresenceLog.d("worker Clear(playersOnly) -> clearing activity")
                    clearActivity()
                    published = null
                    nextAttempt = System.currentTimeMillis() + MIN_PUBLISH_INTERVAL
                } else {
                    DiscordPresenceLog.d("worker Clear(all) -> disabling")
                    enabled = false
                    applicationId = null
                    closeClient()
                    published = null
                    publishState(DiscordPresenceState(DiscordPresenceStatus.Disabled))
                }
            }
        }
    }

    private fun applyConfiguration(message: PresenceMessage.Configure) {
        val previousId = applicationId
        enabled = message.enabled
        applicationId = message.applicationId.takeIf { enabled }
        DiscordPresenceLog.d(
            "worker Configure enabled=$enabled appId=$applicationId previousId=$previousId reconnect=${message.reconnect}",
        )
        if (!enabled || applicationId.isNullOrBlank()) {
            wanted = null
            published = null
            closeClient()
            publishState(DiscordPresenceState(DiscordPresenceStatus.Disabled))
            return
        }
        if (message.reconnect || previousId != applicationId) {
            DiscordPresenceLog.d("worker forcing fresh IPC client")
            closeClient()
            published = null
            nextAttempt = 0L
        }
        ensureConnected()
    }

    /** True when the card is stale; the returned value is the rate-limit gate timestamp. */
    private fun due(now: Long): Long? {
        val current = wanted ?: run {
            DiscordPresenceLog.d("due: no wanted presence")
            return null
        }
        val p = current.presence
        val reason = when (val pub = published) {
            null -> "never published"
            else -> {
                val drifted = if (!p.paused) {
                    val elapsed = (now - pub.at) / 1000.0
                    abs(p.position - (pub.position + elapsed)) > SEEK_TOLERANCE
                } else false
                when {
                    pub.identity != p.identity() -> "identity changed (${pub.identity} -> ${p.identity()})"
                    drifted -> "seek drift > ${SEEK_TOLERANCE.toInt()}s"
                    now - pub.at >= REFRESH_INTERVAL -> "refresh"
                    else -> null
                }
            }
        }
        if (reason == null) {
            DiscordPresenceLog.d("due: no (unchanged card)")
            return null
        }
        DiscordPresenceLog.d("due: YES ($reason), gate at ${nextAttempt - now}ms")
        return nextAttempt
    }

    private fun pump() {
        val now = System.currentTimeMillis()
        val state = client?.state()?.name ?: "none"
        if (state != lastLoggedState) {
            DiscordPresenceLog.d("IPC state -> $state (connected=$connected)")
            lastLoggedState = state
        }
        if (publishPending) {
            DiscordPresenceLog.d("pump: waiting for SET_ACTIVITY acknowledgement")
            return
        }
        if (now < nextAttempt) {
            DiscordPresenceLog.d("pump: waiting ${nextAttempt - now}ms (rate limit / backoff)")
            return
        }
        if (client == null || !connected) {
            if (!ensureConnected()) return
        }

        val current = wanted
        if (current == null) {
            val pub = published
            if (pub == null || pub.identity != IDLE_IDENTITY || now - pub.at >= REFRESH_INTERVAL) {
                DiscordPresenceLog.d("pump: publishing idle card")
                publishActivity(buildDiscordPresenceActivity(null, Instant.ofEpochMilli(now)), IDLE_IDENTITY, 0.0)
            }
            return
        }

        if (due(now) == null) return
        val p = current.presence
        // Bring the clock up to now: the report may have waited out the rate-limit window.
        val position = if (p.paused) p.position
        else p.position + (now - current.reportedAt) / 1000.0
        val card = p.copy(position = position.coerceAtLeast(0.0))
        DiscordPresenceLog.d("pump: publishing player card identity=${card.identity()} position=${card.position.toInt()}")
        publishActivity(buildActivity(card), card.identity(), card.position)
    }

    /**
     * Start (or reuse) the IPC client. `connect()` is deliberately non-blocking: Perigee keeps
     * retrying in the background until Discord's pipe appears. Blocking on a 5s timeout and then
     * closing the client killed that retry loop, which is why presence never appeared when Discord
     * was launched after the app or took a moment to expose the pipe.
     */
    private fun ensureConnected(): Boolean {
        val id = applicationId
        if (id.isNullOrBlank()) {
            DiscordPresenceLog.w("ensureConnected: no valid numeric application id")
            return false
        }
        val current = client
        if (current != null) {
            if (current.isReady()) {
                if (!connected) DiscordPresenceLog.d("existing client became Ready")
                connected = true
                published = null
                nextAttempt = 0L
                if (mutableState.value.status != DiscordPresenceStatus.Connected) {
                    publishState(DiscordPresenceState(DiscordPresenceStatus.Connected))
                }
                return true
            }
            // Already connecting/backing off inside Perigee; its Ready handler will wake us.
            return false
        }
        val now = System.currentTimeMillis()
        if (now < nextAttempt) {
            DiscordPresenceLog.d("ensureConnected: backoff ${nextAttempt - now}ms")
            return false
        }

        DiscordPresenceLog.d("ensureConnected: building PerigeeClient appId=$id")
        publishState(DiscordPresenceState(DiscordPresenceStatus.Connecting))
        val newClient = try {
            PerigeeClient.builder(id)
                .autoReconnect(true)
                .reconnectDelay(Duration.ofSeconds(2), Duration.ofSeconds(30))
                .build()
        } catch (failure: Exception) {
            DiscordPresenceLog.e("could not build PerigeeClient", failure)
            connected = false
            nextAttempt = now + RECONNECT_INTERVAL
            publishState(DiscordPresenceState(DiscordPresenceStatus.Unavailable))
            return false
        }

        newClient.on(Ready::class.java) { ready ->
            if (client === newClient) {
                DiscordPresenceLog.d("Ready: Discord IPC is live")
                connected = true
                published = null
                nextAttempt = 0L
                val user = ready.user()
                val displayName = user?.globalName()?.takeIf(String::isNotBlank)
                    ?: user?.username()?.takeIf(String::isNotBlank)
                publishState(
                    DiscordPresenceState(
                        status = DiscordPresenceStatus.Connected,
                        accountName = displayName,
                        username = user?.username()?.takeIf(String::isNotBlank),
                        userId = user?.id()?.takeIf(String::isNotBlank),
                        avatarHash = user?.avatar()?.takeIf(String::isNotBlank),
                    ),
                )
            }
        }
        newClient.on(Disconnected::class.java) { event ->
            if (client === newClient) {
                connected = false
                published = null
                nextAttempt = System.currentTimeMillis() + RECONNECT_INTERVAL
                publishState(DiscordPresenceState(DiscordPresenceStatus.Unavailable))
                val suffix = if (event.reconnecting()) " (Perigee retrying)" else ""
                DiscordPresenceLog.w(
                    "Disconnected$suffix: ${event.cause()?.message ?: "unknown cause"}",
                )
            }
        }

        generation++
        client = newClient
        lastLoggedState = "none"
        try {
            // No timeout wait: Perigee owns reconnection. A Ready event flips `connected` and
            // the next pump publishes the card that is already waiting in `wanted`.
            newClient.connect()
            DiscordPresenceLog.d("connect() started; state=${newClient.state()}")
        } catch (failure: Exception) {
            DiscordPresenceLog.e("connect() failed immediately", failure)
            closeClient()
            nextAttempt = System.currentTimeMillis() + RECONNECT_INTERVAL
            publishState(DiscordPresenceState(DiscordPresenceStatus.Unavailable))
            return false
        }
        if (newClient.isReady()) {
            connected = true
            published = null
            nextAttempt = 0L
            return true
        }
        return false
    }

    /**
     * Submit SET_ACTIVITY without blocking the worker. Discord can take up to ~10s to acknowledge
     * the first activity; waiting on the future used to stall the worker (and closing a client
     * from that path could hang on the Windows pipe read). The completion is posted back through
     * the worker channel so all state transitions stay single-threaded.
     */
    private fun publishActivity(activity: Activity, identity: String, position: Double) {
        if (publishPending) return
        if (!ensureConnected()) return
        val activeClient = client ?: return
        val currentGeneration = generation
        publishPending = true
        DiscordPresenceLog.d("SET_ACTIVITY submitted identity=$identity position=${position.toInt()}")
        activeClient.setActivity(activity).whenComplete { _, failure ->
            val cause = failure?.let { if (it is java.util.concurrent.CompletionException) it.cause ?: it else it }
            send(
                PresenceMessage.PublishResult(
                    generation = currentGeneration,
                    identity = identity,
                    position = position,
                    failure = cause,
                ),
            )
        }
    }

    private fun clearActivity() {
        val activeClient = client
        if (activeClient == null || !connected) return
        DiscordPresenceLog.d("CLEAR_ACTIVITY submitted")
        activeClient.clearActivity().whenComplete { _, failure ->
            if (failure == null) DiscordPresenceLog.d("CLEAR_ACTIVITY accepted")
            else DiscordPresenceLog.e("CLEAR_ACTIVITY failed", failure)
        }
    }

    private fun closeClient() {
        val current = client
        generation++
        publishPending = false
        client = null
        connected = false
        if (current != null) DiscordPresenceLog.d("closing IPC client")
        current?.let { runCatching { it.close() } }
    }

    private fun publishState(next: DiscordPresenceState) {
        if (!closed.get()) {
            DiscordPresenceLog.d("state -> ${next.status}")
            mutableState.value = next
        }
    }
}

private fun Double.episodeLabel(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun String.discordText(maxCodePoints: Int = 128): String {
    val count = codePointCount(0, length)
    if (count <= maxCodePoints) return this
    val end = offsetByCodePoints(0, maxCodePoints - 1)
    return substring(0, end) + "…"
}
