package com.lelloman.store.recovery

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A truncated or failed command response must never authorize the next repair step. */
internal object RecoveryCommandOutput {
    const val MAX_BYTES = 16 * 1024

    fun read(input: InputStream): String = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (count > MAX_BYTES - output.size()) {
                throw IOException("Recovery command output exceeded its limit; inspect Store before proceeding")
            }
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
}
