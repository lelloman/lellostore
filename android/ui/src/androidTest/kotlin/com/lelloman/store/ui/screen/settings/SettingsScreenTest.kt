package com.lelloman.store.ui.screen.settings

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
