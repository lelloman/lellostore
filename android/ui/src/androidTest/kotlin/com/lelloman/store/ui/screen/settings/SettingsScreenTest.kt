package com.lelloman.store.ui.screen.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.ui.model.ThemeMode
import com.lelloman.store.ui.theme.LellostoreTheme
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun automaticUpdatesRowTogglesThePreference() {
        var newValue: Boolean? = null

        composeRule.setContent {
            LellostoreTheme(themeMode = ThemeMode.Light) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsContent(
                        state = SettingsScreenState(autoUpdateDefault = true),
                        onThemeModeChanged = {},
                        onUpdateCheckIntervalChanged = {},
                        onWifiOnlyDownloadsChanged = {},
                        onAutoUpdateDefaultChanged = { newValue = it },
                        onReleaseChannelDefaultChanged = {},
                        onInstallationChannelEnabledChanged = { _, _ -> },
                        onMoveInstallationChannel = { _, _ -> },
                        onTestLegacyAdb = {},
                        onTestWirelessDebugging = {},
                        onPairWirelessDebugging = {},
                        onOpenDeveloperSettings = {},
                        onServerUrlInputChanged = {},
                        onServerUrlSave = {},
                        onLogoutClick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Automatic updates").performClick()

        composeRule.runOnIdle { assertThat(newValue).isFalse() }
    }

    @Test
    fun installationChannelCanBeDisabledAndMoved() {
        var disabledId: String? = null
        var movedId: String? = null
        var moveOffset: Int? = null

        composeRule.setContent {
            LellostoreTheme(themeMode = ThemeMode.Light) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsContent(
                        state = SettingsScreenState(
                            installationChannels = listOf(
                                InstallationChannelOption(
                                    id = "legacy-adb",
                                    displayName = "ADB on port 5555",
                                    requiresUserInteraction = false,
                                    enabled = true,
                                ),
                                InstallationChannelOption(
                                    id = "package-installer",
                                    displayName = "Android package installer",
                                    requiresUserInteraction = true,
                                    enabled = true,
                                ),
                            )
                        ),
                        onThemeModeChanged = {},
                        onUpdateCheckIntervalChanged = {},
                        onWifiOnlyDownloadsChanged = {},
                        onAutoUpdateDefaultChanged = {},
                        onReleaseChannelDefaultChanged = {},
                        onInstallationChannelEnabledChanged = { id, enabled ->
                            if (!enabled) disabledId = id
                        },
                        onMoveInstallationChannel = { id, offset ->
                            movedId = id
                            moveOffset = offset
                        },
                        onTestLegacyAdb = {},
                        onTestWirelessDebugging = {},
                        onPairWirelessDebugging = {},
                        onOpenDeveloperSettings = {},
                        onServerUrlInputChanged = {},
                        onServerUrlSave = {},
                        onLogoutClick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("ADB on port 5555")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription("Move Android package installer up")
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle {
            assertThat(disabledId).isEqualTo("legacy-adb")
            assertThat(movedId).isEqualTo("package-installer")
            assertThat(moveOffset).isEqualTo(-1)
        }
    }
}
