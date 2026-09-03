package com.lelloman.store.ui.screen.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.lelloman.store.ui.model.ThemeMode
import com.lelloman.store.ui.theme.LellostoreTheme
import org.junit.Rule
import org.junit.Test

class LoginScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun signInActionInvokesCallbackWhenServerIsConfigured() {
        var clicked = false

        composeRule.setContent {
            LellostoreTheme(themeMode = ThemeMode.Light) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LoginScreenContent(
                        state = LoginScreenState(serverUrl = "https://store.lelloman.com"),
                        onServerUrlChanged = {},
                        onLoginClick = { clicked = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Sign in with OIDC")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle { assertThat(clicked).isTrue() }
    }
}
