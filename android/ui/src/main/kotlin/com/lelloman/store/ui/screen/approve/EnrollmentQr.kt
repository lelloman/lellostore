package com.lelloman.store.ui.screen.approve

import com.lelloman.store.domain.config.ServerAddress
import com.lelloman.store.domain.enrollment.UserCode
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * What a scanned enrollment QR code (or a pasted link) names. A TV shows the web admin's
 * `verification_uri_complete` (`https://<store>/admin/device-tokens/approve?code=WDJB-MJHT`), so
 * any phone camera opens the approval page; the JSON `verification_payload`
 * (`{"type":"device-enrollment","version":1,"server_url":…,"user_code":…}`) and a bare code are
 * accepted too. Neither contains a secret.
 */
sealed interface ScannedEnrollment {
    data class Code(val userCode: String) : ScannedEnrollment

    /** A code for a store other than the one LelloStore is signed in to. */
    data class OtherStore(val host: String) : ScannedEnrollment

    data object Invalid : ScannedEnrollment
}

object EnrollmentQr {
    private const val MAX_LENGTH = 2048
    private const val APPROVE_PATH = "/admin/device-tokens/approve"

    fun parse(scanned: String, selectedServer: String): ScannedEnrollment {
        val text = scanned.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return ScannedEnrollment.Invalid
        UserCode.normalize(text)?.let { return ScannedEnrollment.Code(it) }
        return when {
            text.startsWith("{") -> parsePayload(text, selectedServer)
            text.startsWith("https://", ignoreCase = true) -> parseLink(text, selectedServer)
            else -> ScannedEnrollment.Invalid
        }
    }

    private fun parsePayload(text: String, selectedServer: String): ScannedEnrollment = try {
        val json = JSONObject(text)
        if (json.optString("type") != "device-enrollment" || json.optInt("version", -1) != 1) {
            ScannedEnrollment.Invalid
        } else {
            forStore(json.optString("server_url"), json.optString("user_code"), selectedServer)
        }
    } catch (_: JSONException) {
        ScannedEnrollment.Invalid
    }

    private fun parseLink(text: String, selectedServer: String): ScannedEnrollment {
        val uri = runCatching { URI(text) }.getOrNull() ?: return ScannedEnrollment.Invalid
        if (uri.rawPath?.trimEnd('/') != APPROVE_PATH || uri.rawFragment != null) return ScannedEnrollment.Invalid
        val code = uri.rawQuery.orEmpty().split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .singleOrNull { it.first == "code" }?.second
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: return ScannedEnrollment.Invalid
        val origin = runCatching { URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString() }.getOrNull()
            ?: return ScannedEnrollment.Invalid
        return forStore(origin, code, selectedServer)
    }

    private fun forStore(serverUrl: String, code: String, selectedServer: String): ScannedEnrollment {
        val userCode = UserCode.normalize(code) ?: return ScannedEnrollment.Invalid
        val origin = runCatching { ServerAddress.normalize(serverUrl) }.getOrNull() ?: return ScannedEnrollment.Invalid
        val selected = runCatching { ServerAddress.normalize(selectedServer) }.getOrNull()
        return if (origin == selected) {
            ScannedEnrollment.Code(userCode)
        } else {
            ScannedEnrollment.OtherStore(URI(origin).host)
        }
    }
}
