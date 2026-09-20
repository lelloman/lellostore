package com.lelloman.store.remote

import android.util.AtomicFile
import com.lelloman.store.domain.remote.ReceiverInfo
import com.lelloman.store.domain.remote.RemoteInstallOutcome
import com.lelloman.store.domain.remote.RemoteInstallResult
import com.lelloman.store.remoteadb.AdbConnection
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal data class RemoteInstallRecord(
    val identity: String,
    val user: Int,
    val packageName: String,
    val name: String,
    val version: Long,
    val session: Int,
    val committing: Boolean = false,
)

internal class RemoteInstallJournal(file: File) {
    private val storage = AtomicFile(file)
    @Synchronized fun read(): RemoteInstallRecord? {
        if (!storage.baseFile.exists() && !File(storage.baseFile.path + ".bak").exists()) return null
        val json = JSONObject(storage.readFully().toString(Charsets.UTF_8))
        return RemoteInstallRecord(json.getString("identity"), json.getInt("user"),
            json.getString("package"), json.getString("name"), json.getLong("version"),
            json.getInt("session"), json.getBoolean("committing"))
    }
    @Synchronized fun write(record: RemoteInstallRecord) {
        val json = JSONObject().put("identity", record.identity).put("user", record.user)
            .put("package", record.packageName).put("name", record.name).put("version", record.version)
            .put("session", record.session).put("committing", record.committing)
        val output = storage.startWrite()
        try {
            output.write(json.toString().toByteArray())
            storage.finishWrite(output)
        } catch (error: Exception) {
            storage.failWrite(output)
            throw error
        }
    }
    @Synchronized fun clear() = storage.delete()
}

internal class PackageRejectedException(message: String) : Exception(message)
internal class InstallUncertainException(message: String, cause: Exception) : IOException(message, cause)

internal class RemotePackageInstaller(private val journal: RemoteInstallJournal) {
    fun install(
        adb: AdbConnection,
        receiver: ReceiverInfo,
        apk: File,
        packageName: String,
        name: String,
        version: Long,
        progress: (Long) -> Unit,
        committing: () -> Unit,
    ): RemoteInstallResult {
        requirePackageName(packageName)
        check(journal.read() == null) { "Resolve the previous interrupted installation before installing another app" }
        checkUser(adb, receiver.userId)
        if ((installedVersion(adb, receiver.userId, packageName) ?: -1) >= version) {
            return RemoteInstallResult(packageName, name, RemoteInstallOutcome.ALREADY_INSTALLED)
        }
        val created = adb.execute("exec:cmd package install-create -r --user ${receiver.userId} -S ${apk.length()}")
        val session = parseInstallSession(created)
        var record = RemoteInstallRecord(receiver.identity, receiver.userId, packageName, name, version, session)
        journal.write(record)
        try {
            val written = apk.inputStream().use { input ->
                adb.execute("exec:cmd package install-write -S ${apk.length()} $session base.apk -",
                    input, apk.length(), onProgress = progress)
            }
            requireSuccess(written)
            checkUser(adb, receiver.userId)
            committing()
            record = record.copy(committing = true)
            journal.write(record)
            val result = adb.execute("exec:cmd package install-commit $session", timeoutMs = 120_000)
            requireSuccess(result)
            check((installedVersion(adb, receiver.userId, packageName) ?: -1) >= version) {
                "Receiver has not confirmed the installed version"
            }
            journal.clear()
            return RemoteInstallResult(packageName, name, RemoteInstallOutcome.INSTALLED)
        } catch (error: PackageRejectedException) {
            val abandoned = abandon(adb, session)
            // A complete commit rejection is definitive even if Android already removed
            // its session. An uncommitted session still needs cleanup on reconnect.
            if (record.committing || abandoned) journal.clear()
            throw error
        } catch (error: Exception) {
            if (record.committing) throw InstallUncertainException("Installation result is unknown; reconnect this receiver to check it", error)
            if (abandon(adb, session)) journal.clear()
            throw error
        }
    }

    fun reconcile(adb: AdbConnection, receiver: ReceiverInfo): RemoteInstallResult? {
        val record = journal.read() ?: return null
        check(record.identity == receiver.identity && record.user == receiver.userId) {
            "Reconnect the previous receiver and Android user to resolve its interrupted installation"
        }
        val version = installedVersion(adb, record.user, record.packageName)
        val outcome = if (record.committing && version != null && version >= record.version) {
            RemoteInstallOutcome.INSTALLED
        } else {
            if (!abandon(adb, record.session)) {
                throw IOException("Could not resolve the receiver's previous installation session")
            }
            RemoteInstallOutcome.CANCELLED
        }
        journal.clear()
        return RemoteInstallResult(record.packageName, record.name, outcome,
            if (outcome == RemoteInstallOutcome.INSTALLED) "Installed version verified after reconnection" else "Interrupted installation cleared; retry is available")
    }

    private fun abandon(adb: AdbConnection, session: Int): Boolean = runCatching {
        val response = adb.execute("exec:cmd package install-abandon $session", timeoutMs = 10_000)
        response.startsWith("Success") || response.contains("Unknown session") || response.contains("does not exist")
    }.getOrDefault(false)

    companion object {
        fun requirePackageName(value: String) {
            require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid package name" }
        }
        fun requireSuccess(response: String) {
            if (!response.startsWith("Success")) throw PackageRejectedException(response.take(1000).ifEmpty { "Receiver returned no installation result" })
        }
        fun parseInstallSession(response: String): Int = Regex("^Success: created install session \\[(\\d+)]$")
            .matchEntire(response.trim())?.groupValues?.get(1)?.toIntOrNull()
            ?: throw PackageRejectedException(response.take(1000).ifEmpty { "Receiver did not create an installation session" })

        fun checkUser(adb: AdbConnection, user: Int) {
            if (adb.execute("shell:am get-current-user").toIntOrNull() != user) {
                throw IOException("The receiver's Android user changed; reconnect before continuing")
            }
        }
        fun installedVersion(adb: AdbConnection, user: Int, packageName: String): Long? {
            requirePackageName(packageName)
            val packages = adb.execute("shell:pm list packages --user $user $packageName")
            if (packages.lineSequence().none { it.trim() == "package:$packageName" }) return null
            val info = adb.execute("shell:dumpsys package $packageName")
            return Regex("\\bversionCode=(\\d+)").find(info)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw IOException("Cannot read installed version on the receiver")
        }
    }
}
