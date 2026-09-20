package com.lelloman.store.remote

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lelloman.store.MainActivity
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.e2e.FakeAuthStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class PesceEntryDeviceTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var auth: AuthStore

    @Before fun setup() {
        hilt.inject()
        (auth as FakeAuthStore).setNotAuthenticated()
    }

    @Test fun notificationEntryOpensUsbToolsWithoutSplashRedirect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("open_pesce", true)).use {
            awaitText("P2P")
            compose.onNodeWithText("P2P").assertExists()
            compose.onNodeWithText("Install LelloStore").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun loginEntryOpensUsbToolsWithoutAuthentication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            awaitText("P2P · USB tools")
            compose.onNodeWithText("P2P · USB tools").performScrollTo().performClick()
            compose.onNodeWithText("P2P").assertExists()
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
