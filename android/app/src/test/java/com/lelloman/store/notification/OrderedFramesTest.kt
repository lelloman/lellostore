package com.lelloman.store.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OrderedFramesTest {
    @Test fun framesAreProcessedInArrivalOrderOnAMultiThreadedScope() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val mutex = Mutex()
        val processed = Collections.synchronizedList(mutableListOf<String>())
        val count = 2000
        val done = CountDownLatch(count)
        // Mirrors the broker: each frame takes the shared mutex and may suspend.
        val frames = OrderedFrames(scope) { text ->
            mutex.withLock { yield(); processed.add(text) }
            done.countDown()
        }
        try {
            // A routes_begin/routes/routes_end burst followed by many deliveries,
            // offered from one thread as OkHttp's reader does.
            val sent = listOf("routes_begin", "routes", "routes_end") + (1..count - 3).map { "delivery-$it" }
            sent.forEach(frames::offer)
            withTimeout(10_000) { while (!done.await(10, TimeUnit.MILLISECONDS)) yield() }
            assertEquals(sent, processed.toList())
        } finally {
            frames.close()
            scope.cancel()
        }
    }

    @Test fun closingStopsProcessingAfterQueuedFrames() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val processed = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(2)
        val frames = OrderedFrames(scope) { processed.add(it); done.countDown() }
        try {
            frames.offer("a"); frames.offer("b"); frames.close(); frames.offer("late")
            done.await(5, TimeUnit.SECONDS)
            assertEquals(listOf("a", "b"), processed.toList())
        } finally { scope.cancel() }
    }
}
