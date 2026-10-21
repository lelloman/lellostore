package com.lelloman.store.enrollment

import android.os.SystemClock
import com.lelloman.store.domain.api.AppGroupSummary
import com.lelloman.store.domain.api.DeviceTokenAdminApi
import com.lelloman.store.domain.api.DeviceTokenSummary
import com.lelloman.store.domain.auth.AuthStore
import com.lelloman.store.domain.config.ConfigStore
import com.lelloman.store.domain.enrollment.ApprovedEnrollment
import com.lelloman.store.domain.enrollment.DeviceEnrollmentAdminApi
import com.lelloman.store.domain.enrollment.EnrollmentApproval
import com.lelloman.store.domain.enrollment.PendingEnrollment
import com.lelloman.store.ui.model.AuthState
import com.lelloman.store.ui.screen.approve.ApproveDeviceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import com.lelloman.store.domain.auth.AuthState as DomainAuthState

@Singleton
class ApproveDeviceInteractorImpl @Inject constructor(
    authStore: AuthStore,
    private val configStore: ConfigStore,
    private val enrollments: DeviceEnrollmentAdminApi,
    private val deviceTokens: DeviceTokenAdminApi,
) : ApproveDeviceViewModel.Interactor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val authState: StateFlow<AuthState> = authStore.authState
        .map { state ->
            when (state) {
                is DomainAuthState.Loading -> AuthState.Loading
                is DomainAuthState.NotAuthenticated -> AuthState.NotAuthenticated
                is DomainAuthState.Authenticated -> AuthState.Authenticated(state.userEmail, state.deviceToken)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    override fun serverUrl(): String = configStore.serverUrl.value

    override suspend fun isAdmin(): Result<Boolean> = enrollments.isAdmin()

    override suspend fun lookup(userCode: String): Result<PendingEnrollment> = enrollments.lookup(userCode)

    override suspend fun groups(): Result<List<AppGroupSummary>> = deviceTokens.listGroups()

    override suspend fun deviceTokens(): Result<List<DeviceTokenSummary>> = deviceTokens.list()

    override suspend fun approve(userCode: String, approval: EnrollmentApproval): Result<ApprovedEnrollment> =
        enrollments.approve(userCode, approval)

    override suspend fun deny(userCode: String, blockSource: Boolean): Result<Unit> =
        enrollments.deny(userCode, blockSource)

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
