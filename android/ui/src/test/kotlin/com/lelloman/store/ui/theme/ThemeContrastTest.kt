package com.lelloman.store.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeContrastTest {

    @Test
    fun `original Android green remains the light brand accent`() {
        assertThat(LelloStoreLightColorScheme.primary).isEqualTo(Color(0xFF3DDC84))
        assertThat(LelloStoreDarkColorScheme.primary).isEqualTo(Color(0xFF3DDC84))
    }

    @Test
    fun `content colors have accessible contrast in both themes`() {
        val rolePairs = listOf(
            LelloStoreLightColorScheme.primary to LelloStoreLightColorScheme.onPrimary,
            LelloStoreLightColorScheme.primaryContainer to LelloStoreLightColorScheme.onPrimaryContainer,
            LelloStoreLightColorScheme.secondary to LelloStoreLightColorScheme.onSecondary,
            LelloStoreLightColorScheme.secondaryContainer to LelloStoreLightColorScheme.onSecondaryContainer,
            LelloStoreLightColorScheme.tertiary to LelloStoreLightColorScheme.onTertiary,
            LelloStoreLightColorScheme.tertiaryContainer to LelloStoreLightColorScheme.onTertiaryContainer,
            LelloStoreLightColorScheme.background to LelloStoreLightColorScheme.onBackground,
            LelloStoreDarkColorScheme.primary to LelloStoreDarkColorScheme.onPrimary,
            LelloStoreDarkColorScheme.primaryContainer to LelloStoreDarkColorScheme.onPrimaryContainer,
            LelloStoreDarkColorScheme.secondary to LelloStoreDarkColorScheme.onSecondary,
            LelloStoreDarkColorScheme.secondaryContainer to LelloStoreDarkColorScheme.onSecondaryContainer,
            LelloStoreDarkColorScheme.tertiary to LelloStoreDarkColorScheme.onTertiary,
            LelloStoreDarkColorScheme.tertiaryContainer to LelloStoreDarkColorScheme.onTertiaryContainer,
            LelloStoreDarkColorScheme.background to LelloStoreDarkColorScheme.onBackground,
        )

        rolePairs.forEach { (background, foreground) ->
            assertThat(contrastRatio(background, foreground)).isAtLeast(4.5f)
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
