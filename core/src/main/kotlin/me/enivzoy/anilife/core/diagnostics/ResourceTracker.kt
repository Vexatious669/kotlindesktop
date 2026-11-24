package me.enivzoy.anilife.core.diagnostics

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

class NetworkLease internal constructor(private val label: String) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun track(stream: InputStream): InputStream = object : FilterInputStream(stream) {
        override fun close() {
            try {
                super.close()
            } finally {
                closeLease()
            }
        }
    }

    override fun close() = closeLease()

    private fun closeLease() {
        if (closed.compareAndSet(false, true)) ResourceTracker.networkClosed(label)
    }
}

object ResourceTracker {
    private const val MAX_WEB_VIEWS = 3
    private const val MAX_NETWORK_CONNECTIONS = 50
    private val webViews = AtomicInteger()
    private val networkConnections = AtomicInteger()
    private val mpvHandles = AtomicInteger()

    fun webViewCreated(owner: String) {
        val active = webViews.incrementAndGet()
        ActionBreadcrumbs.record("WebView created for $owner")
        if (active > MAX_WEB_VIEWS) logger.warn { "$active browser sandboxes alive; owner=$owner" }
    }

    fun webViewDestroyed() {
        val active = webViews.updateAndGet { (it - 1).coerceAtLeast(0) }
        ActionBreadcrumbs.record("WebView destroyed")
        logger.debug { "browser sandbox destroyed; remaining=$active" }
    }

    fun networkOpened(label: String): NetworkLease {
        val active = networkConnections.incrementAndGet()
        if (active > MAX_NETWORK_CONNECTIONS) logger.warn { "$active network connections active; label=$label" }
        return NetworkLease(label)
    }

    internal fun networkClosed(label: String) {
        val active = networkConnections.updateAndGet { (it - 1).coerceAtLeast(0) }
        logger.debug { "network connection closed; remaining=$active; label=$label" }
    }

    fun mpvCreated() {
        val previous = mpvHandles.getAndIncrement()
        if (previous != 0) logger.error { "mpv instance created while $previous handle(s) were still alive" }
        ActionBreadcrumbs.record("MPV player initialized")
    }

    fun mpvDestroyed() {
        val remaining = mpvHandles.updateAndGet { (it - 1).coerceAtLeast(0) }
        logger.debug { "mpv handle destroyed; remaining=$remaining" }
    }

    internal fun activeWebViewCount(): Int = webViews.get()
    internal fun activeNetworkCount(): Int = networkConnections.get()
    internal fun activeMpvCount(): Int = mpvHandles.get()
}
