package com.lelloman.store.credential

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.lelloman.store.MainActivity
import com.lelloman.store.domain.preferences.UserPreferencesStore
import com.lelloman.store.logger.AuditLog
import com.lelloman.store.ui.model.ThemeMode
import com.lelloman.store.ui.screen.credential.CredentialCaller
import com.lelloman.store.ui.screen.credential.CredentialOutcome
import com.lelloman.store.ui.screen.credential.IncomingCredentialRequest
import com.lelloman.store.ui.screen.credential.IssueCredentialScreen
import com.lelloman.store.ui.screen.credential.IssueCredentialScreenState
import com.lelloman.store.ui.screen.credential.IssueCredentialViewModel
import com.lelloman.store.ui.theme.LellostoreTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.lelloman.store.domain.preferences.ThemeMode as DomainThemeMode

/**
 * Exported entry point of the credential broker (see [CredentialContract]). Another app starts
 * it for result; the user confirms once and the credential goes back only in the result intent.
 */
@AndroidEntryPoint
class IssueCredentialActivity : ComponentActivity() {

    @Inject lateinit var userPreferencesStore: UserPreferencesStore

    @Inject lateinit var audit: AuditLog

    private val viewModel: IssueCredentialViewModel by viewModels()
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tapjacking: drop touches while another window covers this one and hide overlays.
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        enableEdgeToEdge()

        // Only set when started for result, which is the only way the result reaches the caller.
        val callerPackage = callingActivity?.packageName ?: callingPackage
        val caller = callerPackage?.let(::loadCaller)
        viewModel.start(readRequest(callerPackage, caller?.label ?: callerPackage.orEmpty()))

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (state is IssueCredentialScreenState.Finished) deliver(state.outcome, callerPackage)
            }
        }
        setContent {
            val themeMode by userPreferencesStore.themeMode.collectAsState()
            val state by viewModel.state.collectAsState()
            LellostoreTheme(themeMode = themeMode.toUiModel()) {
                IssueCredentialScreen(
                    state = state,
                    caller = caller,
                    onConfirm = viewModel::onConfirm,
                    onCancel = viewModel::onCancel,
                    onOpenStore = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        viewModel.onCancel()
                    },
                )
            }
        }
    }

    private fun readRequest(callerPackage: String?, callerLabel: String): IncomingCredentialRequest = try {
        val intent = intent
        val paramsBundle = intent.getBundleExtra(CredentialContract.EXTRA_PARAMS)
        val params = paramsBundle?.keySet().orEmpty().associateWith { paramsBundle?.getString(it) }
        IncomingCredentialRequest(
            callerPackage = callerPackage,
            callerLabel = callerLabel,
            type = intent.getStringExtra(CredentialContract.EXTRA_CREDENTIAL_TYPE),
            subjectName = intent.getStringExtra(CredentialContract.EXTRA_SUBJECT_NAME),
            replaceExisting = intent.getBooleanExtra(CredentialContract.EXTRA_REPLACE_EXISTING, false),
            params = params.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
            malformed = intent.action != CredentialContract.ACTION_ISSUE_CREDENTIAL || params.values.any { it == null },
        )
    } catch (_: RuntimeException) {
        // A caller-supplied Bundle can fail to unparcel (BadParcelableException and friends).
        IncomingCredentialRequest(callerPackage, callerLabel, type = null, subjectName = null, malformed = true)
    }

    /** Label and icon when Android lets LelloStore see the package; the package name otherwise. */
    private fun loadCaller(packageName: String): CredentialCaller = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        CredentialCaller(
            packageName = packageName,
            label = info.loadLabel(packageManager).toString().trim().ifEmpty { packageName },
            icon = runCatching { info.loadIcon(packageManager).toBitmap(ICON_SIZE_PX, ICON_SIZE_PX).asImageBitmap() }.getOrNull(),
        )
    } catch (_: PackageManager.NameNotFoundException) {
        CredentialCaller(packageName = packageName, label = packageName)
    }

    private fun deliver(outcome: CredentialOutcome, callerPackage: String?) {
        if (delivered) return
        delivered = true
        val type = intent.getStringExtraOrNull(CredentialContract.EXTRA_CREDENTIAL_TYPE)
        when (outcome) {
            is CredentialOutcome.Issued -> setResult(
                RESULT_OK,
                Intent()
                    .putExtra(CredentialContract.EXTRA_CREDENTIAL_TYPE, outcome.type)
                    .putExtra(CredentialContract.EXTRA_CREDENTIAL, outcome.credential),
            )
            is CredentialOutcome.Failed -> setResult(
                RESULT_CANCELED,
                Intent().putExtra(CredentialContract.EXTRA_ERROR, outcome.error.wireValue),
            )
        }
        // Never the credential: only who asked, for what type, and how it ended.
        audit.record(
            "ipc.credential_request",
            mapOf(
                "package" to callerPackage,
                "type" to type?.take(MAX_AUDITED_TYPE_LENGTH),
                "outcome" to when (outcome) {
                    is CredentialOutcome.Issued -> "issued"
                    is CredentialOutcome.Failed -> outcome.error.wireValue
                },
            ),
        )
        finish()
    }

    private fun Intent.getStringExtraOrNull(name: String): String? = try {
        getStringExtra(name)
    } catch (_: RuntimeException) {
        null
    }

    private fun DomainThemeMode.toUiModel(): ThemeMode = when (this) {
        DomainThemeMode.System -> ThemeMode.System
        DomainThemeMode.Light -> ThemeMode.Light
        DomainThemeMode.Dark -> ThemeMode.Dark
    }

    private companion object {
        const val ICON_SIZE_PX = 192
        const val MAX_AUDITED_TYPE_LENGTH = 64
    }
}
