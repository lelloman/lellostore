package com.lelloman.store.ui.screen.detail

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.download.DownloadFailureKind
import com.lelloman.store.domain.download.DownloadResult
import com.lelloman.store.domain.download.DownloadState
import com.lelloman.store.ui.R
import org.junit.Test

class InstallationResultCardTest {

    private val failure = DownloadResult.Failed("ADB install stream failed: closed")

    @Test
    fun `no card while an operation runs, even with an earlier failure`() {
        for (state in listOf(
            DownloadState.PENDING,
            DownloadState.DOWNLOADING,
            DownloadState.VERIFYING,
            DownloadState.INSTALLING,
        )) {
            assertThat(installationResultCard(state, failure)).isNull()
            assertThat(installationResultCard(state, null)).isNull()
        }
    }

    @Test
    fun `no card without anything to report`() {
        assertThat(installationResultCard(null, null)).isNull()
        assertThat(installationResultCard(DownloadState.PERMISSION_REQUIRED, failure)).isNull()
    }

    @Test
    fun `a failure always has a title and a message`() {
        val card = installationResultCard(null, DownloadResult.Failed("  "))!!

        assertThat(card.isError).isTrue()
        assertThat(card.title).isEqualTo(R.string.download_failed)
        assertThat(card.message).isEqualTo(R.string.download_failed_hint)
        assertThat(card.technicalDetails).isNull()

        assertThat(installationResultCard(DownloadState.FAILED, null)!!.message).isEqualTo(R.string.download_failed_hint)
        assertThat(installationResultCard(DownloadState.FAILED, failure)!!.technicalDetails).isEqualTo(failure.reason)
    }

    @Test
    fun `incompatible signatures get their own explanation`() {
        val card = installationResultCard(
            DownloadState.FAILED,
            DownloadResult.Failed("INSTALL_FAILED_UPDATE_INCOMPATIBLE", DownloadFailureKind.INCOMPATIBLE_SIGNATURE),
        )!!

        assertThat(card.title).isEqualTo(R.string.incompatible_signature_title)
        assertThat(card.message).isEqualTo(R.string.incompatible_signature_message)
    }

    @Test
    fun `a later outcome is not shown as an error`() {
        val completed = installationResultCard(DownloadState.COMPLETED, failure)!!

        assertThat(completed.isError).isFalse()
        assertThat(completed.title).isEqualTo(R.string.download_completed)
        assertThat(completed.technicalDetails).isNull()
        assertThat(installationResultCard(DownloadState.CANCELLED, failure)!!.isError).isFalse()
        assertThat(installationResultCard(DownloadState.AWAITING_CONFIRMATION, failure)!!.isError).isFalse()
    }
}
