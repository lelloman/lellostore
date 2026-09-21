package com.lelloman.store.ui.screen.pesce

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.remote.*
import com.lelloman.store.ui.theme.LellostoreTheme
import org.junit.Rule
import org.junit.Test

class PesceScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val receiver = ReceiverInfo("serial:test", "Nord test receiver", "14", 34, 0, emptyList())

    @Test fun transferProgressStaysVisibleWhenControlsScroll() {
        val actions = mutableListOf<PesceAction>()
        compose.setContent {
            LellostoreTheme {
                PesceScreenContent(RemoteDeviceState(phase = RemoteConnectionPhase.READY, receiver = receiver,
                    operation = RemoteOperationPhase.TRANSFERRING, activeApp = "LelloStore", bytes = 500, totalBytes = 1000),
                    PescePickerState(), false, actions::add, {})
            }
        }
        compose.onNodeWithText("Sending APK over USB… 50%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Install catalog apps").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Sending APK over USB… 50%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Stop").performClick()
        assertThat(actions).contains(PesceAction.Cancel)
    }

    @Test fun networkOperationShowsVisibleActivityWithoutByteProgress() {
        compose.setContent {
            LellostoreTheme {
                PesceScreenContent(RemoteDeviceState(phase = RemoteConnectionPhase.READY, receiver = receiver,
                    operation = RemoteOperationPhase.TESTING_TCP), PescePickerState(), false, {}, {})
            }
        }
        compose.onNodeWithText("Testing network ADB…").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test fun offlineCopyIsAvailableForAuthorizedReceiver() {
        val actions = mutableListOf<PesceAction>()
        compose.setContent {
            LellostoreTheme {
                PesceScreenContent(RemoteDeviceState(phase = RemoteConnectionPhase.READY, receiver = receiver),
                    PescePickerState(), false, actions::add, {})
            }
        }
        compose.onNodeWithText("Nord test receiver").assertExists()
        compose.onNodeWithText("Install LelloStore").performScrollTo().assertIsEnabled().performClick()
        assertThat(actions).contains(PesceAction.Copy)
    }

    @Test fun installationIsDisabledUntilReceiverAuthorization() {
        compose.setContent {
            LellostoreTheme {
                PesceScreenContent(RemoteDeviceState(phase = RemoteConnectionPhase.AUTHORIZING),
                    PescePickerState(), false, {}, {})
            }
        }
        compose.onNodeWithText("Install LelloStore").performScrollTo().assertIsNotEnabled()
    }

    @Test fun signedOutCatalogOffersLoginWhileUsbToolsStayOpen() {
        var signIn = false
        compose.setContent {
            LellostoreTheme {
                PesceScreenContent(RemoteDeviceState(phase = RemoteConnectionPhase.READY, receiver = receiver),
                    PescePickerState(open = true), false, {}, { signIn = true })
            }
        }
        compose.onNodeWithText("Sign in").performClick()
        assertThat(signIn).isTrue()
    }

    @Test fun tcpIpRequiresExplicitEnableAction() {
        val actions = mutableListOf<PesceAction>()
        compose.setContent {
            LellostoreTheme {
                PesceScreenContent(RemoteDeviceState(phase = RemoteConnectionPhase.READY, receiver = receiver),
                    PescePickerState(), false, actions::add, {})
            }
        }
        compose.onNodeWithText("Enable ADB TCP/IP").performScrollTo().performClick()
        assertThat(actions).doesNotContain(PesceAction.EnableTcp)
        compose.onNodeWithText("Enable", useUnmergedTree = true).performClick()
        assertThat(actions).contains(PesceAction.EnableTcp)
    }
}
