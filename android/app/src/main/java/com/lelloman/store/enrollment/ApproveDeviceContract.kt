package com.lelloman.store.enrollment

import com.lelloman.store.credential.CredentialContract

/**
 * The inter-app contract of [ApproveDeviceActivity]: another app (PezzotTV on the phone) asks the
 * administrator to approve a device's enrollment. These strings are a public API; never rename
 * them. Nothing secret goes in or out: the user code only identifies the request, and the device
 * that started it receives its credential from the store itself.
 */
object ApproveDeviceContract {
    const val ACTION_APPROVE_DEVICE = "com.lelloman.store.action.APPROVE_DEVICE"

    /** In (optional): the user code the device shows, any case, with or without the dash. */
    const val EXTRA_USER_CODE = "com.lelloman.store.extra.USER_CODE"

    /** In (optional): the name to suggest for the device (1..100 characters). */
    const val EXTRA_DEVICE_NAME = "com.lelloman.store.extra.DEVICE_NAME"

    /** Out on `RESULT_OK`: the id of the approved (or rekeyed) device token. */
    const val EXTRA_DEVICE_TOKEN_ID = "com.lelloman.store.extra.DEVICE_TOKEN_ID"

    /** Out on `RESULT_OK`: true when an existing device token was replaced. */
    const val EXTRA_REKEYED = "com.lelloman.store.extra.REKEYED"

    /** Out on `RESULT_CANCELED`: an [com.lelloman.store.ui.screen.approve.ApprovalError] wire value. */
    const val EXTRA_ERROR = CredentialContract.EXTRA_ERROR
}
