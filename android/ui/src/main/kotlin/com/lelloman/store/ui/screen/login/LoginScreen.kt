package com.lelloman.store.ui.screen.login

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lelloman.store.ui.R
import com.lelloman.store.ui.components.LelloStoreBrandMark
import com.lelloman.store.ui.components.lelloStoreButtonColors
import com.lelloman.store.ui.model.AuthResult
import com.lelloman.store.ui.model.ThemeMode
import com.lelloman.store.ui.theme.Green20
import com.lelloman.store.ui.theme.LelloStoreSpacing
import com.lelloman.store.ui.theme.LellostoreTheme
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

@Composable
fun LoginScreen(
    onNavigateToMain: () -> Unit,
    onAuthResponse: (AuthorizationResponse?, AuthorizationException?, onResult: (AuthResult) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            val response = AuthorizationResponse.fromIntent(data)
            val exception = AuthorizationException.fromIntent(data)

            if (response != null || exception != null) {
                onAuthResponse(response, exception) { authResult ->
                    viewModel.onAuthResult(authResult)
                }
            } else {
                // Intent had data but couldn't parse auth response
                viewModel.onAuthResult(AuthResult.Cancelled)
            }
        } else {
            viewModel.onAuthResult(AuthResult.Cancelled)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LoginScreenEvent.LaunchAuth -> {
                    authLauncher.launch(event.intent)
                }
                is LoginScreenEvent.NavigateToMain -> {
                    onNavigateToMain()
                }
            }
        }
    }

    LoginScreenContent(
        state = state,
        onServerUrlChanged = viewModel::onServerUrlChanged,
        onLoginClick = viewModel::onLoginClick,
        modifier = modifier,
    )
}

@Composable
internal fun LoginScreenContent(
    state: LoginScreenState,
    onServerUrlChanged: (String) -> Unit,
    onLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        val isWide = maxWidth >= 600.dp && maxWidth > maxHeight
        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = LelloStoreSpacing.hero, vertical = LelloStoreSpacing.xLarge),
                horizontalArrangement = Arrangement.spacedBy(LelloStoreSpacing.hero),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoginBrandPanel(
                    modifier = Modifier.weight(1f),
                    brandMarkSize = 104.dp,
                )
                LoginForm(
                    state = state,
                    onServerUrlChanged = onServerUrlChanged,
                    onLoginClick = onLoginClick,
                    modifier = Modifier.weight(1f).widthIn(max = 480.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(LelloStoreSpacing.xLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LoginBrandPanel(
                    modifier = Modifier.widthIn(max = 480.dp),
                    brandMarkSize = 88.dp,
                )
                Spacer(Modifier.height(LelloStoreSpacing.xxLarge))
                LoginForm(
                    state = state,
                    onServerUrlChanged = onServerUrlChanged,
                    onLoginClick = onLoginClick,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
            }
        }
    }
}

@Composable
private fun LoginBrandPanel(
    brandMarkSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LelloStoreBrandMark(contentDescription = null, size = brandMarkSize)
        Spacer(Modifier.height(LelloStoreSpacing.large))
        Text(
            text = stringResource(R.string.login_welcome),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(LelloStoreSpacing.small))
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginForm(
    state: LoginScreenState,
    onServerUrlChanged: (String) -> Unit,
    onLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(LelloStoreSpacing.xLarge)) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChanged,
                label = { Text(stringResource(R.string.login_server_url)) },
                placeholder = { Text(stringResource(R.string.login_server_url_placeholder)) },
                isError = state.serverUrlError != null,
                supportingText = state.serverUrlError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(LelloStoreSpacing.large))

            if (state.error != null) {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(LelloStoreSpacing.large))
            }

            Button(
                onClick = onLoginClick,
                enabled = !state.isLoading && state.serverUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = lelloStoreButtonColors(),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Green20,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Text(stringResource(R.string.login_sign_in_oidc))
                }
            }
        }
    }
}

@Preview(name = "Login · light", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(
    name = "Login · dark",
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LoginScreenPreview() {
    val isDark = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    LellostoreTheme(themeMode = if (isDark) ThemeMode.Dark else ThemeMode.Light) {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoginScreenContent(
                state = LoginScreenState(serverUrl = "https://store.lelloman.com"),
                onServerUrlChanged = {},
                onLoginClick = {},
            )
        }
    }
}
