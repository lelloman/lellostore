package com.lelloman.store.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Processes one connection's frames strictly in arrival order.
 *
 * Launching a coroutine per frame lets frames reach the broker mutex out of
 * order on a multi-threaded scope: a routes_end handled before its routes frame
 * committed an empty route set for the whole connection, so every delivery was
 * skipped as inactive_route_or_session.
 */
internal class OrderedFrames(scope: CoroutineScope, private val process: suspend (String) -> Unit) {
    private val frames = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch { for (text in frames) process(text) }
    }

    fun offer(text: String) { frames.trySend(text) }

    fun close() { frames.close() }
}
