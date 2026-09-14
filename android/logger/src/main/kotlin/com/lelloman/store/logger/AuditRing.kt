package com.lelloman.store.logger

import java.io.File

/** Whole-record FIFO: eight segments, at most 4 MiB total, oldest segment evicted first. */
class AuditRing(
    private val directory: File,
    private val segmentBytes: Int = 512 * 1024,
    private val segmentCount: Int = 8,
) {
    init {
        require(segmentBytes > 0 && segmentCount > 0)
    }

    @Synchronized
    fun append(record: String) {
        require(!record.contains('\n') && !record.contains('\r'))
        val bytes = (record + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= segmentBytes)
        check(directory.isDirectory || directory.mkdirs())
        val current = segment(0)
        if (current.length() + bytes.size > segmentBytes) {
            val oldest = segment(segmentCount - 1)
            check(!oldest.exists() || oldest.delete())
            for (index in segmentCount - 2 downTo 0) {
                val source = segment(index)
                if (source.exists()) check(source.renameTo(segment(index + 1)))
            }
        }
        // A killed writer may leave a partial last line; discard it before appending.
        if (current.exists()) {
            java.io.RandomAccessFile(current, "rw").use { file ->
                var end = file.length()
                while (end > 0) {
                    file.seek(end - 1)
                    if (file.read() == 10) break
                    end--
                }
                file.setLength(end)
            }
        }
        current.appendBytes(bytes)
    }

    @Synchronized
    fun snapshot(): String = buildString {
        for (index in segmentCount - 1 downTo 0) {
            val file = segment(index)
            if (file.exists()) {
                val text = file.readText(Charsets.UTF_8)
                val end = text.lastIndexOf('\n')
                if (end >= 0) append(text.substring(0, end + 1))
            }
        }
    }

    private fun segment(index: Int) = File(directory, "audit-$index.jsonl")
}
