package com.lelloman.store.localdata.auth

import com.lelloman.store.domain.config.ServerAddress
import org.json.JSONObject
import java.io.File

/** A read-only catalog credential provisioned by root into the app's no-backup directory. */
data class DeviceToken(val name: String, val serverUrl: String, val token: String) {
    // Keep the secret out of logs and crash reports.
    override fun toString(): String = "DeviceToken(name=$name, serverUrl=$serverUrl)"
}

/**
 * Reads `noBackupFilesDir/device-token.json`:
 * `{"version":1,"server_url":"https://…","name":"Living room TV","token":"lellostore_dev_…"}`.
 * The file is written by root (not by the app), so a plain read is enough.
 */
object DeviceTokenFile {
    const val FILE_NAME = "device-token.json"
    const val SECRET_PREFIX = "lellostore_dev_"
    private const val MAX_BYTES = 4096L
    private const val MAX_NAME_LENGTH = 100
    private val SECRET = Regex("^${SECRET_PREFIX}[0-9a-f]{64}$")

    sealed interface Result {
        data object Absent : Result
        data class Valid(val token: DeviceToken) : Result
        /** [reason] is a short code safe to log; it never contains file content. */
        data class Invalid(val reason: String) : Result
    }

    fun read(file: File): Result {
        if (!file.exists()) return Result.Absent
        return try {
            if (!file.isFile) return Result.Invalid("not_a_file")
            if (file.length() > MAX_BYTES) return Result.Invalid("too_large")
            parse(file.readText(Charsets.UTF_8))
        } catch (_: java.io.IOException) {
            Result.Invalid("unreadable")
        } catch (_: SecurityException) {
            Result.Invalid("unreadable")
        }
    }

    fun parse(text: String): Result {
        val json = try {
            JSONObject(text)
        } catch (_: org.json.JSONException) {
            return Result.Invalid("malformed")
        }
        if (json.optInt("version", -1) != 1) return Result.Invalid("unsupported_version")
        val serverUrl = runCatching { ServerAddress.normalize(json.optString("server_url")) }.getOrNull()
            ?: return Result.Invalid("invalid_server_url")
        val name = json.optString("name").trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return Result.Invalid("invalid_name")
        val token = json.optString("token")
        if (!SECRET.matches(token)) return Result.Invalid("invalid_token")
        return Result.Valid(DeviceToken(name, serverUrl, token))
    }
}
