package com.lelloman.store.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundUpdateEventsTest {
    @Test
    fun `background connection requires opt in and running foreground service`() {
        assertThat(shouldKeepCatalogConnection(false, true, true, true)).isTrue()
        assertThat(shouldKeepCatalogConnection(false, true, false, true)).isFalse()
        assertThat(shouldKeepCatalogConnection(false, true, true, false)).isFalse()
        assertThat(shouldKeepCatalogConnection(false, false, true, true)).isFalse()
        assertThat(shouldKeepCatalogConnection(true, true, false, false)).isTrue()
        assertThat(shouldKeepCatalogConnection(true, false, true, true)).isFalse()
    }

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
