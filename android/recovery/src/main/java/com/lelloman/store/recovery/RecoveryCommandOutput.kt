package com.lelloman.store.recovery

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A truncated or failed command response must never authorize the next repair step. */
internal object RecoveryCommandOutput {
    const val MAX_BYTES = 16 * 1024

    fun read(input: InputStream, completionMarker: String): String = input.use {
        require(completionMarker.isNotEmpty())
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) throw IOException("Recovery command ended without completion; inspect Store before proceeding")
            if (count > MAX_BYTES - output.size()) {
                throw IOException("Recovery command output exceeded its limit; inspect Store before proceeding")
            }
            output.write(buffer, 0, count)
            val response = output.toString(Charsets.UTF_8.name())
            if (response.endsWith(completionMarker)) return@use response.removeSuffix(completionMarker)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }
}
