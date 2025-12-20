package com.lelloman.store.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lelloman.store.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CatalogE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val mockServerRule = MockServerRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        hiltRule.inject()
        TestServerUrlHolder.serverUrl = mockServerRule.baseUrl
    }

    @Test
    fun catalogScreen_displaysAppsFromServer() {
        // Given: Server returns list of apps
        mockServerRule.enqueueAppsResponse(
            listOf(
                MockApp(packageName = "com.example.calculator", name = "Calculator"),
                MockApp(packageName = "com.example.calendar", name = "Calendar"),
                MockApp(packageName = "com.example.notes", name = "Notes"),
            )
        )

        // When: App launches and catalog loads (happens automatically after splash)
        // Wait for apps to be displayed
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Calculator").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Then: Apps are displayed
        composeRule.onNodeWithText("Calculator").assertIsDisplayed()
        composeRule.onNodeWithText("Calendar").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").assertIsDisplayed()
    }

    @Test
    fun catalogScreen_searchFiltersApps() {
        // Given: Server returns list of apps
        mockServerRule.enqueueAppsResponse(
            listOf(
                MockApp(packageName = "com.example.calculator", name = "Calculator"),
                MockApp(packageName = "com.example.calendar", name = "Calendar"),
                MockApp(packageName = "com.example.notes", name = "Notes"),
            )
        )

        // Wait for apps to be displayed
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Calculator").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // When: User types in search
        composeRule.onNodeWithText("Search apps").performClick()
        composeRule.onNodeWithText("Search apps").performTextInput("Cal")

        // Then: Only matching apps are shown
        composeRule.onNodeWithText("Calculator").assertIsDisplayed()
        composeRule.onNodeWithText("Calendar").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").assertDoesNotExist()
    }

    @Test
    fun catalogScreen_showsEmptyStateWhenNoApps() {
        // Given: Server returns empty list
        mockServerRule.enqueueAppsResponse(emptyList())

        // Wait for the empty state to be displayed
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("No apps available").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Then: Empty state message is shown
        composeRule.onNodeWithText("No apps available").assertIsDisplayed()
    }

    @Test
    fun catalogScreen_navigatesToAppDetail() {
        // Given: Server returns apps and app detail
        mockServerRule.enqueueAppsResponse(
            listOf(
                MockApp(packageName = "com.example.calculator", name = "Calculator"),
            )
        )
        mockServerRule.enqueueAppDetailResponse(
            MockAppDetail(
                packageName = "com.example.calculator",
                name = "Calculator",
                description = "A simple calculator app",
                versions = listOf(
                    MockAppVersion(
                        versionCode = 1,
                        versionName = "1.0.0",
                        size = 1024 * 1024,
                        sha256 = "abc123",
                    )
                ),
            )
        )

        // Wait for app to be displayed
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Calculator").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // When: User clicks on the app
        composeRule.onNodeWithText("Calculator").performClick()

        // Then: App detail screen is shown with description
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("A simple calculator app").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("A simple calculator app").assertIsDisplayed()
    }
}
