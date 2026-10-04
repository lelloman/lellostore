package com.lelloman.store.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import android.os.PowerManager
import com.lelloman.store.ui.screen.settings.hasPushBatteryExemption
import com.lelloman.store.ui.screen.settings.openPushBatterySettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 28])
class StartupBatteryWarningTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun startupWarningHasSettingsActionAndExpiresAfterOneMinute() {
        val helper = NotificationHelper(context)
        assertFalse(context.hasPushBatteryExemption())
        assertTrue(helper.showStartupBatteryWarning())
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val notification = manager.getNotification(NotificationHelper.STARTUP_BATTERY_NOTIFICATION_ID)
        assertNotNull(notification)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(shadowOf(notification.contentIntent).savedIntent.getBooleanExtra("open_battery_settings", false))
        assertEquals(1, notification.actions.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertNull(manager.getNotification(NotificationHelper.STARTUP_BATTERY_NOTIFICATION_ID))
    }

    @Test fun grantingExemptionClearsWarningAndDisabledNotificationsRequestFallback() {
        val helper = NotificationHelper(context)
        helper.showStartupBatteryWarning()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(context.hasPushBatteryExemption())
        assertTrue(helper.showStartupBatteryWarning())
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertNull(manager.getNotification(NotificationHelper.STARTUP_BATTERY_NOTIFICATION_ID))
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, false)
        manager.setNotificationsEnabled(false)
        assertFalse(helper.showStartupBatteryWarning())
    }

    @Test fun batterySettingsLinkTargetsThisApp() {
        context.openPushBatterySettings()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
