package com.lelloman.store.ui.screen.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreBrandMark
import com.lelloman.store.ui.theme.Green20
import com.lelloman.store.ui.theme.LelloGreen
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToMain: () -> Unit,
    isLoggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(isLoggedIn) {
        delay(500) // Brief splash delay
        if (isLoggedIn) {
            onNavigateToMain()
        } else {
            onNavigateToLogin()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = LelloGreen,
        contentColor = Green20,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LelloStoreBrandMark(contentDescription = null, size = 96.dp)
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.splash_title),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(
                color = Green20,
                trackColor = Green20.copy(alpha = 0.18f),
            )
        }
    }
}
