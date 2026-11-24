package me.enivzoy.anilife.core.diagnostics

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

object MemoryProfiler {
    private const val SAMPLE_INTERVAL_MS = 3_000L
    private const val DEBUG_INTERVAL_MS = 10_000L
    private const val OOM_THRESHOLD = 0.85
    private const val SPIKE_BYTES = 200L * 1024L * 1024L
    private val running = AtomicBoolean(false)
    @Volatile private var job: Job? = null
    @Volatile private var debugJob: Job? = null
    @Volatile private var lastUsedBytes = 0L

    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        lastUsedBytes = usedBytes()
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                sample()
            }
        }
        debugJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(DEBUG_INTERVAL_MS)
                logHeap()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        job?.cancel()
        debugJob?.cancel()
        job = null
        debugJob = null
    }

    private fun sample() {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        val usedMb = used / 1024 / 1024
        val maxMb = max / 1024 / 1024
        val delta = used - lastUsedBytes
        lastUsedBytes = used
        if (used > max * OOM_THRESHOLD) {
            logger.warn { "heap usage ${usedMb}MB / ${maxMb}MB (${(used.toDouble() / max * 100).roundToInt()}%)" }
        }
        if (delta > SPIKE_BYTES) {
            logger.warn { "heap spike ${delta / 1024 / 1024}MB in ${SAMPLE_INTERVAL_MS / 1000}s; ${threadDump()}" }
        }
    }

    private fun logHeap() {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        logger.debug { "heap usage ${used / 1024 / 1024}MB / ${runtime.maxMemory() / 1024 / 1024}MB" }
    }

    private fun usedBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun threadDump(): String = Thread.getAllStackTraces().entries.joinToString("\n") { (thread, frames) ->
        "\"${thread.name}\" ${thread.state}\n" + frames.take(12).joinToString("\n") { "  at $it" }
    }
}
