package me.enivzoy.anilife.core.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticsTest {
    @Test
    fun `breadcrumbs keep the last fifty actions`() {
        ActionBreadcrumbs.clear()
        repeat(60) { index -> ActionBreadcrumbs.record("action $index") }
        val recent = ActionBreadcrumbs.recent()
        assertEquals(50, recent.size)
        assertEquals("action 10", recent.first())
        assertEquals("action 59", recent.last())
        ActionBreadcrumbs.clear()
    }

    @Test
    fun `resource tracker records browser sandbox lifecycle`() {
        ActionBreadcrumbs.clear()
        ResourceTracker.webViewCreated("test provider")
        ResourceTracker.webViewDestroyed()
        assertEquals(
            listOf("WebView created for test provider", "WebView destroyed"),
            ActionBreadcrumbs.recent(),
        )
        ActionBreadcrumbs.clear()
    }
}
