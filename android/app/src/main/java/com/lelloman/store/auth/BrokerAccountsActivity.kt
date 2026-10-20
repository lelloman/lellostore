package com.lelloman.store.auth

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.lelloman.authenticator.broker.AccountsModel
import com.lelloman.authenticator.broker.BrokerAccountsScreen
import com.lelloman.authenticator.broker.LelloAuthBroker
import com.lelloman.store.ui.R
import com.lelloman.store.ui.theme.LellostoreTheme

/** Non-exported settings surface; only the library's validated link activity is exported. */
class BrokerAccountsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LelloAuthBroker.isConfigured) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val settings = LelloAuthBroker.settings(this)
        setContent {
            val requireAuthentication by settings.requireUserAuthenticationForApproval.collectAsState()
            var failed by remember { mutableStateOf(false) }
            LellostoreTheme {
                Surface {
                    Column(Modifier.fillMaxSize().padding(top = 32.dp, bottom = 24.dp)) {
                        Text(stringResource(R.string.broker_accounts), Modifier.padding(20.dp), style = MaterialTheme.typography.headlineMedium)
                        Row(Modifier.padding(horizontal = 20.dp)) {
                            Text(stringResource(R.string.broker_require_authentication), Modifier.weight(1f))
                            Switch(checked = requireAuthentication, onCheckedChange = { value ->
                                try { settings.setRequireUserAuthenticationForApproval(value) }
                                catch (error: Exception) {
                                    android.util.Log.w("SignInBroker", "Could not save approval setting", error)
                                    failed = true
                                }
                            })
                        }
                        BrokerAccountsScreen(Modifier.weight(1f))
                    }
                    if (failed) AlertDialog(onDismissRequest = { failed = false },
                        text = { Text(stringResource(R.string.broker_setting_failed)) },
                        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(android.R.string.ok)) } })
                }
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
}
