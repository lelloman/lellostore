package com.lelloman.store.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreDiagnosticsTest {
    private fun event(at: Long, name: String, reason: String = "") =
        """{"timestamp_ms":$at,"event":"$name","fields":{"reason":"$reason"}}"""

    private val snapshot = listOf(
        event(1000, "push.frame"),
        event(2000, "push.delivery_skipped", "inactive_route_or_session"),
        "",
        event(3000, "http.response"),
        event(4000, "push.delivery_skipped", "battery_policy"),
    ).joinToString("\n")

    @Test fun filtersCaseInsensitivelyAndKeepsNewestLast() {
        assertEquals(listOf(event(2000, "push.delivery_skipped", "inactive_route_or_session"),
            event(4000, "push.delivery_skipped", "battery_policy")).joinToString("\n"),
            StoreDiagnostics.query(snapshot, "DELIVERY_SKIPPED", 0, 200))
    }

    @Test fun limitKeepsTheNewestMatches() {
        assertEquals(event(4000, "push.delivery_skipped", "battery_policy"),
            StoreDiagnostics.query(snapshot, "delivery", 0, 1))
    }

    @Test fun sinceDropsOlderAndUnparseableEvents() {
        assertEquals(listOf(event(3000, "http.response"), event(4000, "push.delivery_skipped", "battery_policy")).joinToString("\n"),
            StoreDiagnostics.query(snapshot + "\nnot json", null, 3000, 200))
    }

    @Test fun noFilterReturnsEverythingNonBlank() {
        assertEquals(4, StoreDiagnostics.query(snapshot, " ".trim().ifEmpty { null }, 0, 200).lines().size)
    }
}
