package com.lelloman.store.notification

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.DownloadProgress
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.domain.download.InstallationMode
import com.lelloman.store.notification.OperationOutcomeNotifier.Companion.detectOutcomes
import org.junit.Test

class OperationOutcomeNotifierTest {
    @Test
    fun `foreground entry entering failed is reported`() {
        val failed = progress("a", DownloadState.FAILED, reason = "boom")
        val outcomes = detectOutcomes(
            mapOf("a" to progress("a", DownloadState.INSTALLING)),
            mapOf("a" to failed),
        )
        assertThat(outcomes).containsExactly(failed)
    }

    @Test
    fun `foreground entry entering completed is reported even without a previous entry`() {
        val completed = progress("a", DownloadState.COMPLETED)
        assertThat(detectOutcomes(emptyMap(), mapOf("a" to completed))).containsExactly(completed)
    }

    @Test
    fun `entry already terminal is not reported again`() {
        val failed = progress("a", DownloadState.FAILED)
        assertThat(detectOutcomes(mapOf("a" to failed), mapOf("a" to failed))).isEmpty()
    }

    @Test
    fun `background entries are ignored`() {
        val outcomes = detectOutcomes(
            mapOf("a" to progress("a", DownloadState.INSTALLING, InstallationMode.BACKGROUND)),
            mapOf("a" to progress("a", DownloadState.FAILED, InstallationMode.BACKGROUND)),
        )
        assertThat(outcomes).isEmpty()
    }

    @Test
    fun `non outcome terminal states are ignored`() {
        val outcomes = detectOutcomes(
            mapOf(
                "a" to progress("a", DownloadState.DOWNLOADING),
                "b" to progress("b", DownloadState.INSTALLING),
                "c" to progress("c", DownloadState.DOWNLOADING),
            ),
            mapOf(
                "a" to progress("a", DownloadState.CANCELLED),
                "b" to progress("b", DownloadState.AWAITING_CONFIRMATION),
                "c" to progress("c", DownloadState.PERMISSION_REQUIRED),
            ),
        )
        assertThat(outcomes).isEmpty()
    }

    @Test
    fun `several packages are reported independently`() {
        val outcomes = detectOutcomes(
            mapOf(
                "a" to progress("a", DownloadState.INSTALLING),
                "b" to progress("b", DownloadState.DOWNLOADING),
            ),
            mapOf(
                "a" to progress("a", DownloadState.COMPLETED),
                "b" to progress("b", DownloadState.FAILED),
            ),
        )
        assertThat(outcomes.map { it.packageName }).containsExactly("a", "b")
    }

    private fun progress(
        packageName: String,
        state: DownloadState,
        mode: InstallationMode = InstallationMode.FOREGROUND,
        reason: String? = null,
    ) = DownloadProgress(packageName, 1f, 1, 1, state, mode, reason)
}
