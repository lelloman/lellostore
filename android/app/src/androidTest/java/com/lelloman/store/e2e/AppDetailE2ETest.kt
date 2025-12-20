package com.lelloman.store.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class AppDetailE2ETest {

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
    fun appDetailScreen_displaysAppInfo() {
        // Given: Server returns catalog and app detail
        mockServerRule.enqueueAppsResponse(
            listOf(
                MockApp(packageName = "com.example.notes", name = "Notes"),
            )
        )
        mockServerRule.enqueueAppDetailResponse(
            MockAppDetail(
                packageName = "com.example.notes",
                name = "Notes",
                description = "A note-taking application for organizing your thoughts",
                versions = listOf(
                    MockAppVersion(
                        versionCode = 2,
                        versionName = "2.0.0",
                        size = 5 * 1024 * 1024,
                        sha256 = "def456",
                    ),
                    MockAppVersion(
                        versionCode = 1,
                        versionName = "1.0.0",
                        size = 4 * 1024 * 1024,
                        sha256 = "abc123",
                    ),
                ),
            )
        )

        // Wait for catalog to load
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Notes").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // When: Navigate to app detail
        composeRule.onNodeWithText("Notes").performClick()

        // Then: App detail is displayed with all info
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("A note-taking application for organizing your thoughts").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Check description
        composeRule.onNodeWithText("A note-taking application for organizing your thoughts").assertIsDisplayed()

        // Check version info
        composeRule.onNodeWithText("v2.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("About").assertIsDisplayed()
        composeRule.onNodeWithText("Latest Version").assertIsDisplayed()

        // Check install button (app is not installed)
        composeRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun appDetailScreen_showsVersionHistory() {
        // Given: Server returns app with multiple versions
        mockServerRule.enqueueAppsResponse(
            listOf(
                MockApp(packageName = "com.example.app", name = "Multi Version App"),
            )
        )
        mockServerRule.enqueueAppDetailResponse(
            MockAppDetail(
                packageName = "com.example.app",
                name = "Multi Version App",
                versions = listOf(
                    MockAppVersion(
                        versionCode = 3,
                        versionName = "3.0.0",
                        size = 10 * 1024 * 1024,
                        sha256 = "v3hash",
                    ),
                    MockAppVersion(
                        versionCode = 2,
                        versionName = "2.0.0",
                        size = 8 * 1024 * 1024,
                        sha256 = "v2hash",
                    ),
                    MockAppVersion(
                        versionCode = 1,
                        versionName = "1.0.0",
                        size = 5 * 1024 * 1024,
                        sha256 = "v1hash",
                    ),
                ),
            )
        )

        // Wait for catalog and navigate
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Multi Version App").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("Multi Version App").performClick()

        // Wait for detail to load
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Version History").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Then: Version history is shown
        composeRule.onNodeWithText("Version History").assertIsDisplayed()
        composeRule.onNodeWithText("v2.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("v1.0.0").assertIsDisplayed()
    }

    @Test
    fun appDetailScreen_backNavigatesToCatalog() {
        // Given: Server returns catalog and app detail
        mockServerRule.enqueueAppsResponse(
            listOf(
                MockApp(packageName = "com.example.app", name = "Test App"),
            )
        )
        mockServerRule.enqueueAppDetailResponse(
            MockAppDetail(
                packageName = "com.example.app",
                name = "Test App",
                versions = listOf(
                    MockAppVersion(
                        versionCode = 1,
                        versionName = "1.0.0",
                        size = 1024 * 1024,
                        sha256 = "hash",
                    )
                ),
            )
        )

        // Navigate to detail
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Test App").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("Test App").performClick()

        // Wait for detail
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Install").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Server will need to respond to apps request again when going back
        mockServerRule.enqueueAppsResponse(
            listOf(
                MockApp(packageName = "com.example.app", name = "Test App"),
            )
        )

        // When: Press back
        composeRule.onNodeWithText("Back", useUnmergedTree = true)
            .performClick()

        // Then: Catalog is shown again
        composeRule.waitUntil(timeoutMillis = 10_000) {
            try {
                composeRule.onNodeWithText("Search apps").assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("Search apps").assertIsDisplayed()
    }
}
