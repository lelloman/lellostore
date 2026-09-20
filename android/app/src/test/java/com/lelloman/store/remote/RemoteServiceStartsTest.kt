package com.lelloman.store.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteServiceStartsTest {
    @Test fun `immediate failure waits for foreground promotion before stopping`() {
        val starts = RemoteServiceStarts()
        val events = mutableListOf<String>()
        starts.request { events += "start requested" }
        starts.stopIfIdle({ false }) { events += "stopped" }
        assertThat(events).containsExactly("start requested")
        events += "foreground"
        starts.delivered()
        starts.stopIfIdle({ false }) { events += "stopped" }
        assertThat(events).containsExactly("start requested", "foreground", "stopped").inOrder()
    }

    @Test fun `rapid retries must deliver every pending start before shutdown`() {
        val starts = RemoteServiceStarts()
        var stopped = false
        starts.request { }
        starts.request { }
        starts.delivered()
        starts.stopIfIdle({ false }) { stopped = true }
        assertThat(stopped).isFalse()
        starts.delivered()
        starts.stopIfIdle({ false }) { stopped = true }
        assertThat(stopped).isTrue()
    }

    @Test fun `connected receiver keeps promoted service running`() {
        val starts = RemoteServiceStarts()
        starts.request { }
        starts.delivered()
        assertThat(starts.stopIfIdle({ true }) { error("Active service stopped") }).isFalse()
    }

    @Test fun `rejected start does not leave shutdown waiting forever`() {
        val starts = RemoteServiceStarts()
        assertThrows(IllegalStateException::class.java) {
            starts.request { throw IllegalStateException("Start rejected") }
        }
        assertThat(starts.stopIfIdle({ false }) { }).isTrue()
    }
}
