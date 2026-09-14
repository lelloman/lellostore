package com.lelloman.store.logger

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuditRingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rotationRetainsNewestWholeRecordsWithinByteBudgetAndSurvivesRestart() {
        val directory = temporary.newFolder()
        val ring = AuditRing(directory, segmentBytes = 20, segmentCount = 3)
        repeat(100) { ring.append("{\"n\":$it}") }
        val snapshot = AuditRing(directory, 20, 3).snapshot()
        assertThat(snapshot).endsWith("{\"n\":99}\n")
        assertThat(snapshot).doesNotContain("{\"n\":0}")
        assertThat(directory.listFiles()!!.sumOf { it.length() }).isAtMost(60L)
        val numbers = snapshot.lineSequence().filter { it.isNotBlank() }
            .map { it.removePrefix("{\"n\":").removeSuffix("}").toInt() }.toList()
        assertThat(numbers).isInOrder()
    }

    @Test fun interruptedAppendIsDiscardedBeforeNextRecord() {
        val directory = temporary.newFolder()
        File(directory, "audit-0.jsonl").writeText("{}\n{broken")
        val ring = AuditRing(directory)
        assertThat(ring.snapshot()).isEqualTo("{}\n")
        ring.append("{\"ok\":true}")
        assertThat(ring.snapshot()).isEqualTo("{}\n{\"ok\":true}\n")
    }

    @Test fun concurrentWritersDoNotInterleaveRecords() {
        val ring = AuditRing(temporary.newFolder())
        val pool = Executors.newFixedThreadPool(4)
        repeat(200) { n -> pool.submit { ring.append("{\"n\":$n}") } }
        pool.shutdown()
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        assertThat(ring.snapshot().lines().filter { it.isNotEmpty() }.toSet()).hasSize(200)
    }

    @Test fun byteBudgetCountsUtf8AndRejectsOversizedRecords() {
        val directory = temporary.newFolder()
        val ring = AuditRing(directory, 10, 2)
        repeat(20) { ring.append("ééé") }
        assertThat(directory.listFiles()!!.sumOf { it.length() }).isAtMost(20L)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ring.append("x".repeat(11)) }
    }
}
