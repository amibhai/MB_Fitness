package com.swastik.mbfitness.work

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.core.net.toUri
import com.swastik.mbfitness.data.AppDatabase
import java.util.concurrent.ExecutionException

data class SimChoice(val id: Int, val label: String)

/** What the phone itself allows. Only readings and intents: nothing here changes a setting. */
open class DeviceChecks(private val context: Context) {
    private val packageName get() = context.packageName

    open fun exactAlarmsAllowed(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else {
            null
        }

    open fun batteryExempt(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    open fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Null where the phone has no such feature, or can't say. Blocks, so call it off the main thread. */
    open fun hibernationExempt(): Boolean? = try {
        when (PackageManagerCompat.getUnusedAppRestrictionsStatus(context).get()) {
            UnusedAppRestrictionsConstants.DISABLED -> true
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE, UnusedAppRestrictionsConstants.ERROR -> null
            else -> false
        }
    } catch (_: ExecutionException) {
        null
    }

    /** How many SIMs the phone can hold. Needs no permission, so it works before any is granted. */
    open fun simSlots(): Int {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) telephony.activeModemCount else slotsBeforeAndroid11(telephony)
    }

    // The only way on Android 8 to 10, which is why this deprecated call stays.
    @Suppress("DEPRECATION")
    private fun slotsBeforeAndroid11(telephony: TelephonyManager): Int = telephony.phoneCount

    /**
     * Needs no permission, so it works before anything is granted. Each slot is asked, because a
     * phone with two SIMs and no default reports "unknown" when asked about SIMs in general.
     */
    open fun hasSim(): Boolean {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return (0 until simSlots()).any { telephony.getSimState(it) == TelephonyManager.SIM_STATE_READY }
    }

    open fun canListSims(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Empty until the permission is given, which is only asked for when picking a SIM. */
    open fun activeSims(): List<SimChoice> {
        if (!canListSims()) return emptyList()
        return try {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map { info ->
                SimChoice(info.subscriptionId, "${info.displayName} (SIM ${info.simSlotIndex + 1})")
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /**
     * True when a phone with two SIMs has no default for SMS, so it asks every time. Nobody is
     * there to answer when a reminder goes, so it fails. Needs no permission.
     */
    open fun phoneAsksWhichSim(): Boolean {
        if (!hasSim() || simSlots() < 2) return false
        return SubscriptionManager.getDefaultSmsSubscriptionId() == SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /** Can only be told when the SIM list can be read. Otherwise the choice is assumed to stand. */
    open fun chosenSimMissing(chosen: Int?): Boolean {
        if (chosen == null || !canListSims()) return false
        return activeSims().none { it.id == chosen }
    }

    open fun installedOutsideStore(): Boolean {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                installerBeforeAndroid11()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return installer !in KNOWN_STORES
    }

    // The only way on Android 8 to 10, which is why this deprecated call stays.
    @Suppress("DEPRECATION")
    private fun installerBeforeAndroid11(): String? = context.packageManager.getInstallerPackageName(packageName)

    /** Why the app last stopped, if it wasn't a normal stop (SPEC 13). */
    open fun lastExitReason(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val exits = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1)
        return when (exits.firstOrNull()?.reason) {
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "It crashed"
            ApplicationExitInfo.REASON_ANR -> "It stopped responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "The phone ran low on memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android stopped it for using too much"
            ApplicationExitInfo.REASON_FREEZER -> "Android stopped it in the background"
            else -> null
        }
    }

    open fun databaseBytes(): Long = context.getDatabasePath(AppDatabase.FILE_NAME).length()

    private companion object {
        val KNOWN_STORES = setOf(
            "com.android.vending",
            "com.sec.android.app.samsungapps",
            "com.xiaomi.mipicks",
            "com.heytap.market",
            "com.oppo.market",
            "com.vivo.appstore",
            "com.amazon.venezia",
        )
    }
}

/** The system screens the one-tap fixes open (SPEC 11.1, 13). */
object DeviceIntents {
    private fun packageUri(context: Context) = "package:${context.packageName}".toUri()

    fun appInfo(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun dateAndTime() = Intent(Settings.ACTION_DATE_SETTINGS)

    fun exactAlarms(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))
        } else {
            null
        }

    /** Null on a phone without this feature. Android throws rather than saying so politely. */
    fun hibernation(context: Context): Intent? = try {
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
    } catch (_: UnsupportedOperationException) {
        null
    }

    /** Sideloaded on purpose, so the Play rule against asking for this doesn't apply (SPEC 11.2). */
    @android.annotation.SuppressLint("BatteryLife")
    fun battery(context: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    fun notifications(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** A brand's own battery or autostart screen, where one is known to exist. */
    fun brandBattery(manufacturer: String): List<Intent> {
        val components = when (manufacturer.trim().lowercase()) {
            "xiaomi", "redmi", "poco" -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            "samsung" -> listOf("com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity")
            "oppo", "realme" -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            )
            "oneplus" -> listOf("com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            "vivo", "iqoo" -> listOf("com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            else -> emptyList()
        }
        return components.map { (app, screen) -> Intent().setComponent(ComponentName(app, screen)) }
    }

    /** False if the screen couldn't open, which happens: these aren't guaranteed on every phone. */
    fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
