package com.lelloman.store.enrollment

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
import com.lelloman.store.ui.screen.approve.ApprovalOutcome
import com.lelloman.store.ui.screen.approve.ApproveDeviceRequest
import com.lelloman.store.ui.screen.approve.ApproveDeviceScreen
import com.lelloman.store.ui.screen.approve.ApproveDeviceScreenState
import com.lelloman.store.ui.screen.approve.ApproveDeviceViewModel
import com.lelloman.store.ui.screen.credential.CredentialCaller
import com.lelloman.store.ui.theme.LellostoreTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.lelloman.store.domain.preferences.ThemeMode as DomainThemeMode

/**
 * Approves another device's enrollment. Opened from Settings → Approve a device, or by another
 * app for result with [ApproveDeviceContract.ACTION_APPROVE_DEVICE]. It has the hardening of
 * IssueCredentialActivity: touches are dropped while the window is obscured, other apps'
 * overlays are hidden, and it never joins a task or the recents list.
 */
@AndroidEntryPoint
class ApproveDeviceActivity : ComponentActivity() {

    @Inject lateinit var userPreferencesStore: UserPreferencesStore

    @Inject lateinit var audit: AuditLog

    private val viewModel: ApproveDeviceViewModel by viewModels()
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tapjacking: drop touches while another window covers this one and hide overlays.
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        enableEdgeToEdge()

        val external = intent.action == ApproveDeviceContract.ACTION_APPROVE_DEVICE
        // Only set when started for result, which is the only way the result reaches the caller.
        val callerPackage = if (external) callingActivity?.packageName else null
        val caller = callerPackage?.takeIf { it != packageName }?.let(::loadCaller)
        viewModel.start(readRequest(external, callerPackage, caller))

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (state is ApproveDeviceScreenState.Finished) deliver(state.outcome, external, callerPackage)
            }
        }
        setContent {
            val themeMode by userPreferencesStore.themeMode.collectAsState()
            val state by viewModel.state.collectAsState()
            LellostoreTheme(themeMode = themeMode.toUiModel()) {
                ApproveDeviceScreen(
                    state = state,
                    caller = caller,
                    onCodeChanged = viewModel::onCodeChanged,
                    onScanned = viewModel::onScanned,
                    onLookup = viewModel::onLookup,
                    onModeChanged = viewModel::onModeChanged,
                    onReplaceTokenSelected = viewModel::onReplaceTokenSelected,
                    onGroupToggled = viewModel::onGroupToggled,
                    onNameChanged = viewModel::onNameChanged,
                    onApprove = viewModel::onApprove,
                    onDeny = viewModel::onDeny,
                    onApproveAnother = viewModel::onApproveAnother,
                    onBack = viewModel::onBack,
                    onClose = viewModel::onClose,
                    onOpenStore = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        viewModel.onClose()
                    },
                )
            }
        }
    }

    private fun readRequest(external: Boolean, callerPackage: String?, caller: CredentialCaller?): ApproveDeviceRequest {
        if (!external) return ApproveDeviceRequest()
        return try {
            ApproveDeviceRequest(
                callerPackage = callerPackage,
                callerLabel = caller?.label ?: callerPackage.orEmpty(),
                external = true,
                userCode = intent.getStringExtra(ApproveDeviceContract.EXTRA_USER_CODE),
                deviceName = intent.getStringExtra(ApproveDeviceContract.EXTRA_DEVICE_NAME),
            )
        } catch (_: RuntimeException) {
            // Caller-supplied extras can fail to unparcel.
            ApproveDeviceRequest(callerPackage = callerPackage, external = true, malformed = true)
        }
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

    private fun deliver(outcome: ApprovalOutcome, external: Boolean, callerPackage: String?) {
        if (delivered) return
        delivered = true
        when (outcome) {
            is ApprovalOutcome.Approved -> setResult(
                RESULT_OK,
                Intent()
                    .putExtra(ApproveDeviceContract.EXTRA_DEVICE_TOKEN_ID, outcome.deviceTokenId)
                    .putExtra(ApproveDeviceContract.EXTRA_REKEYED, outcome.rekeyed),
            )
            is ApprovalOutcome.Failed -> setResult(
                RESULT_CANCELED,
                Intent().putExtra(ApproveDeviceContract.EXTRA_ERROR, outcome.error.wireValue),
            )
        }
        // Who asked and how it ended; never a code.
        audit.record(
            "enrollment.approval",
            mapOf(
                "package" to if (external) callerPackage else packageName,
                "outcome" to when (outcome) {
                    is ApprovalOutcome.Approved -> if (outcome.rekeyed) "rekeyed" else "approved"
                    is ApprovalOutcome.Failed -> outcome.error.wireValue
                },
            ),
        )
        finish()
    }

    private fun DomainThemeMode.toUiModel(): ThemeMode = when (this) {
        DomainThemeMode.System -> ThemeMode.System
        DomainThemeMode.Light -> ThemeMode.Light
        DomainThemeMode.Dark -> ThemeMode.Dark
    }

    private companion object {
        const val ICON_SIZE_PX = 192
    }
}
