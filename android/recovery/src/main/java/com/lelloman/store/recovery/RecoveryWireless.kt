package com.lelloman.store.recovery

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object RecoveryWireless {
    fun pair(context: Context, code: String) = synchronized(RecoveryAdbConnectionManager::class.java) {
        require(code.matches(Regex("\\d{6}"))) { "Enter the six-digit code from Android Settings" }
        val endpoint = AtomicReference<Pair<String, Int>?>()
        val discovered = CountDownLatch(1)
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address: InetAddress?, port: Int ->
            if (address != null && port > 0) {
                endpoint.set(address.hostAddress!! to port)
                discovered.countDown()
            }
        }
        mdns.start()
        try {
            check(discovered.await(15, TimeUnit.SECONDS)) {
                "Keep Android's pairing-code dialog visible in split screen and try a fresh code"
            }
        } finally {
            mdns.stop()
        }
        val (host, port) = requireNotNull(endpoint.get())
        val adb = RecoveryAdbConnectionManager.getInstance(context)
        if (adb.isConnected) adb.disconnect()
        check(adb.pair(host, port, code)) { "Android rejected the pairing code" }
    }
}
