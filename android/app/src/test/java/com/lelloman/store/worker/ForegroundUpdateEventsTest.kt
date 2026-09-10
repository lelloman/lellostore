package com.lelloman.store.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundUpdateEventsTest {
    @Test
    fun `catalog URL uses websocket scheme and normalized path`() {
        assertThat(ForegroundCatalogEventConnection.catalogEventsUrl("https://store.example/"))
            .isEqualTo("wss://store.example/api/events")
        assertThat(ForegroundCatalogEventConnection.catalogEventsUrl("http://localhost:8080"))
            .isEqualTo("ws://localhost:8080/api/events")
    }

    @Test
    fun `unsupported server URL has no event endpoint`() {
        assertThat(ForegroundCatalogEventConnection.catalogEventsUrl("store.example")).isNull()
    }

    @Test
    fun `only catalog change messages trigger checks`() {
        assertThat(ForegroundCatalogEventConnection.isCatalogChangedEvent("""{"type":"catalog_changed"}"""))
            .isTrue()
        assertThat(ForegroundCatalogEventConnection.isCatalogChangedEvent("""{"type":"unknown"}"""))
            .isFalse()
    }
}
