package me.enivzoy.anilife.core.diagnostics

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue

private val logger = KotlinLogging.logger {}

object ActionBreadcrumbs {
    private const val MAX_ENTRIES = 50
    private val entries = ConcurrentLinkedQueue<String>()

    fun record(action: String) {
        entries.add(action)
        while (entries.size > MAX_ENTRIES) entries.poll()
        logger.debug { "breadcrumb: $action" }
    }

    fun recent(): List<String> = entries.toList()

    fun clear() {
        entries.clear()
    }

    fun dump() {
        val snapshot = recent()
        logger.error { "action breadcrumbs (${snapshot.size})" }
        snapshot.forEachIndexed { index, action -> logger.error { "${index + 1}. $action" } }
    }
}
