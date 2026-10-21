package com.lelloman.store.enrollment

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.lelloman.store.BuildConfig
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.config.ServerAddress
import com.lelloman.store.domain.enrollment.DeviceEnrollmentApi
import com.lelloman.store.domain.enrollment.EnrollmentPollResult
import com.lelloman.store.domain.enrollment.EnrollmentRequest
import com.lelloman.store.domain.enrollment.EnrollmentStartResult
import com.lelloman.store.domain.preferences.UpdateCheckInterval
import com.lelloman.store.localdata.auth.DeviceTokenAuthStore
import com.lelloman.store.localdata.auth.DeviceTokenFile
import com.lelloman.store.logger.Logger
import com.lelloman.store.ui.screen.enrollment.EnrollmentCompletion
import com.lelloman.store.ui.screen.enrollment.EnrollmentViewModel
import com.lelloman.store.ui.screen.settings.UpdateCheckIntervalOption
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class EnrollmentInteractorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val api: DeviceEnrollmentApi,
    private val deviceToken: DeviceTokenAuthStore,
    private val setup: EnrolledDeviceSetup,
    private val logger: Logger,
) : EnrollmentViewModel.Interactor {

    override fun serverUrl(): String = configStore.serverUrl.value

    override fun defaultDeviceName(): String {
        val configured = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()
        return configured?.takeIf { it.isNotEmpty() } ?: Build.MODEL.orEmpty().trim()
    }

    override suspend fun start(serverUrl: String, name: String): EnrollmentStartResult {
        val result = api.start(
            serverUrl,
            EnrollmentRequest(
                name = name,
                model = Build.MODEL?.trim()?.take(MAX_MODEL_LENGTH)?.takeIf { it.isNotEmpty() },
                androidSdk = Build.VERSION.SDK_INT,
                clientVersion = BuildConfig.VERSION_NAME.take(MAX_VERSION_LENGTH),
            ),
        )
        logger.audit("enrollment.start", mapOf("outcome" to result.javaClass.simpleName))
        return result
    }

    override suspend fun poll(serverUrl: String, deviceCode: String): EnrollmentPollResult =
        api.poll(serverUrl, deviceCode).also { result ->
            if (result !is EnrollmentPollResult.Pending) {
                logger.audit("enrollment.poll", mapOf("outcome" to result.javaClass.simpleName))
            }
        }

    override suspend fun complete(serverUrl: String, credential: String): EnrollmentCompletion {
        val parsed = DeviceTokenFile.parse(credential)
        check(parsed is DeviceTokenFile.Result.Valid) {
            "Invalid device credential (${(parsed as? DeviceTokenFile.Result.Invalid)?.reason})"
        }
        // The token belongs to the store that issued it, the one this device enrolled with, even if
        // a misconfigured proxy made the server name another origin in the file.
        val origin = ServerAddress.normalize(serverUrl)
        val token = parsed.token.let { if (it.serverUrl == origin) it else it.copy(serverUrl = origin) }
        if (token !== parsed.token) logger.audit("enrollment.server_url_mismatch", mapOf("server" to parsed.token.serverUrl))
        deviceToken.provision(token)
        val applied = setup.apply()
        return EnrollmentCompletion(
            deviceName = token.name,
            automaticUpdates = true,
            updateCheckInterval = applied.updateCheckInterval.toOption(),
            updateConnection = true,
        )
    }

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()

    private fun UpdateCheckInterval.toOption(): UpdateCheckIntervalOption = when (this) {
        UpdateCheckInterval.Hours6 -> UpdateCheckIntervalOption.Hours6
        UpdateCheckInterval.Hours12 -> UpdateCheckIntervalOption.Hours12
        UpdateCheckInterval.Hours24 -> UpdateCheckIntervalOption.Hours24
        UpdateCheckInterval.Manual -> UpdateCheckIntervalOption.Manual
    }

    private companion object {
        const val MAX_MODEL_LENGTH = 100
        const val MAX_VERSION_LENGTH = 64
    }
}
