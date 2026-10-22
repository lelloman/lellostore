package com.lelloman.store.auth

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.lelloman.authenticator.broker.AccountsModel
import com.lelloman.authenticator.broker.BrokerAccountsScreen
import com.lelloman.authenticator.broker.LelloAuthBroker
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.ui.R
import com.lelloman.store.ui.model.ThemeMode
import com.lelloman.store.ui.theme.LellostoreTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.lelloman.store.domain.preferences.ThemeMode as DomainThemeMode

/** Non-exported settings surface; only the library's validated link activity is exported. */
@AndroidEntryPoint
class BrokerAccountsActivity : FragmentActivity() {
    @Inject lateinit var userPreferencesStore: UserPreferencesStore

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LelloAuthBroker.isConfigured) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val settings = LelloAuthBroker.settings(this)
        setContent {
            val requireAuthentication by settings.requireUserAuthenticationForApproval.collectAsState()
            val themeMode by userPreferencesStore.themeMode.collectAsState()
            var failed by remember { mutableStateOf(false) }
            LellostoreTheme(themeMode = themeMode.toUiModel()) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.broker_accounts)) },
                            navigationIcon = {
                                TextButton(onClick = { finish() }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.content_description_back))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                            ),
                        )
                    },
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding)) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        value = requireAuthentication,
                                        role = Role.Switch,
                                        onValueChange = { value ->
                                            try {
                                                settings.setRequireUserAuthenticationForApproval(value)
                                            } catch (error: Exception) {
                                                android.util.Log.w("SignInBroker", "Could not save approval setting", error)
                                                failed = true
                                            }
                                        },
                                    )
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.broker_require_authentication),
                                    Modifier.weight(1f).padding(vertical = 12.dp),
                                )
                                Switch(checked = requireAuthentication, onCheckedChange = null,
                                    modifier = Modifier.padding(start = 12.dp))
                            }
                            BrokerAccountsScreen(Modifier.weight(1f))
                        }
                    }
                }
                if (failed) AlertDialog(onDismissRequest = { failed = false },
                    text = { Text(stringResource(R.string.broker_setting_failed)) },
                    confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(android.R.string.ok)) } })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (LelloAuthBroker.isConfigured) {
            LelloAuthBroker.settings(this).refresh()
            ViewModelProvider(this)[AccountsModel::class.java].foreground()
        }
    }

    override fun onStop() {
        if (LelloAuthBroker.isConfigured) ViewModelProvider(this)[AccountsModel::class.java].background()
        super.onStop()
    }

    private fun DomainThemeMode.toUiModel(): ThemeMode = when (this) {
        DomainThemeMode.System -> ThemeMode.System
        DomainThemeMode.Light -> ThemeMode.Light
        DomainThemeMode.Dark -> ThemeMode.Dark
    }
}
