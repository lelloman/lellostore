package com.lelloman.store.remote

import com.google.common.truth.Truth.assertThat
import com.lelloman.store.domain.remote.ReceiverInfo
import com.lelloman.store.domain.remote.RemoteInstallOutcome
import com.lelloman.store.remoteadb.AdbConnection
import io.mockk.*
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream

class RemotePackageInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private val receiver = ReceiverInfo("serial:receiver", "Receiver", "14", 34, 10, emptyList())
    private val adb = mockk<AdbConnection>()
    private val journal = mockk<RemoteInstallJournal>(relaxed = true)
    private val installer = RemotePackageInstaller(journal)
    private var record: RemoteInstallRecord? = null
    private var installed = false
    private var commitReply = "Success"
    private var commitFailure: IOException? = null
    private val commands = mutableListOf<String>()

    private fun setup() {
        every { journal.read() } answers { record }
        every { journal.write(any()) } answers { record = firstArg() }
        every { journal.clear() } answers { record = null }
        every { adb.execute(any(), any(), any(), any(), any()) } answers {
            val command = firstArg<String>()
            commands += command
            when {
                command == "shell:am get-current-user" -> "10"
                command.startsWith("shell:pm list packages") -> if (installed) "package:com.example.app" else ""
                command.startsWith("shell:dumpsys") -> "  versionCode=9 minSdk=24"
                command.startsWith("exec:cmd package install-create") -> "Success: created install session [42]"
                command.startsWith("exec:cmd package install-write") -> {
                    assertThat(secondArg<InputStream>().readBytes()).isEqualTo("apk".toByteArray())
                    lastArg<(Long) -> Unit>()(3)
                    "Success: streamed 3 bytes"
                }
                command.startsWith("exec:cmd package install-commit") -> {
                    commitFailure?.let { throw it }
                    installed = commitReply == "Success"
                    commitReply
                }
                command.startsWith("exec:cmd package install-abandon") -> "Success"
                else -> error("Unexpected command: $command")
            }
        }
    }
    private fun install() = installer.install(adb, receiver, temp.newFile().apply { writeText("apk") },
        "com.example.app", "Example", 9, {}, {})

    @Test fun `install streams to receiver user then checks receiver version`() {
        setup()
        assertThat(install().outcome).isEqualTo(RemoteInstallOutcome.INSTALLED)
        assertThat(commands).contains("exec:cmd package install-create -r --user 10 -S 3")
        assertThat(commands.last()).isEqualTo("shell:dumpsys package com.example.app")
        assertThat(record).isNull()
    }
    @Test fun `equal or newer receiver version is skipped without opening an install session`() {
        setup(); installed = true
        assertThat(install().outcome).isEqualTo(RemoteInstallOutcome.ALREADY_INSTALLED)
        assertThat(commands.none { it.contains("install-create") }).isTrue()
    }
    @Test fun `signature rejection is reported without uninstall or downgrade`() {
        setup(); commitReply = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"
        assertThat(assertThrows(PackageRejectedException::class.java) { install() }.message).contains("INCOMPATIBLE")
        assertThat(record).isNull()
        assertThat(commands.none { it.contains("uninstall") || it.contains(" -d ") }).isTrue()
    }
    @Test fun `lost commit response is journalled and reconciled without resubmission`() {
        setup(); commitFailure = IOException("disconnected")
        assertThrows(InstallUncertainException::class.java) { install() }
        assertThat(record?.committing).isTrue()
        installed = true
        val result = installer.reconcile(adb, receiver)
        assertThat(result?.outcome).isEqualTo(RemoteInstallOutcome.INSTALLED)
        assertThat(commands.count { it.contains("install-commit") }).isEqualTo(1)
        assertThat(record).isNull()
    }
    @Test fun `different receiver cannot reconcile an interrupted installation`() {
        setup()
        record = RemoteInstallRecord("serial:other", 10, "com.example.app", "Example", 9, 42, true)
        assertThrows(IllegalStateException::class.java) { installer.reconcile(adb, receiver) }
        assertThat(commands).isEmpty()
        assertThat(record).isNotNull()
    }
    @Test fun `unfinished session is abandoned on reconnection`() {
        setup()
        record = RemoteInstallRecord(receiver.identity, 10, "com.example.app", "Example", 9, 42)
        assertThat(installer.reconcile(adb, receiver)?.outcome).isEqualTo(RemoteInstallOutcome.CANCELLED)
        assertThat(commands).contains("exec:cmd package install-abandon 42")
        assertThat(record).isNull()
    }
    @Test fun `changed Android user stops installation`() {
        setup()
        every { adb.execute("shell:am get-current-user", any(), any(), any(), any()) } returns "0"
        assertThrows(IOException::class.java) { install() }
        assertThat(commands.none { it.contains("install-create") }).isTrue()
    }
    @Test fun `shell metacharacters cannot enter package commands`() {
        for (pkg in listOf("com.test;reboot", "com.test\nreboot", "com.test --user 0", "com.test'")) {
            assertThrows(IllegalArgumentException::class.java) { RemotePackageInstaller.requirePackageName(pkg) }
        }
    }
}
