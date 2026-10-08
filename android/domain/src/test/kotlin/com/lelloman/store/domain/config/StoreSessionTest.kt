package com.lelloman.store.domain.config

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StoreSessionTest {
    @Test fun `switch cannot overlap active operations and late operations are rejected`() = runTest {
        val session = StoreSession()
        val release = CompletableDeferred<Unit>()
        val oldEpoch = session.epoch
        val operation = launch { session.use { release.await() } }
        runCurrent()
        assertTrue(runCatching { session.change { error("must not enter") } }.exceptionOrNull() is IllegalStateException)
        release.complete(Unit)
        operation.join()
        session.change { assertTrue(runCatching { session.use {} }.isFailure) }
        assertTrue(runCatching { session.use(oldEpoch) { error("must not enter") } }.isFailure)
        session.use { }
    }

    @Test fun `failed operations and switches release the gate`() = runTest {
        val session = StoreSession()
        runCatching { session.use { error("operation failed") } }
        runCatching { session.change { error("setup failed") } }
        session.use { session.use { } }
        session.change { }
    }

    @Test fun `server origins are canonical and reject ambiguous destinations`() {
        assertEquals("https://store.example", ServerAddress.normalize(" HTTPS://Store.Example:443/ "))
        assertEquals("https://store.example:8443", ServerAddress.normalize("https://store.example:8443"))
        for (input in listOf("http://store.example", "https://user:secret@store.example", "https://store.example/path", "https://store.example?q=1", "https://store.example/#x", "https://store.example:99999")) {
            assertTrue(input, runCatching { ServerAddress.normalize(input) }.isFailure)
        }
    }
}
