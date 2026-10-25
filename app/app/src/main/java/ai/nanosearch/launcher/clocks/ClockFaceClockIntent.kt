package ai.nanosearch.launcher.clocks

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast

/** Derive the system Clock package from public contracts; never launch protected OEM APIs. */
object ClockFaceClockIntent {
    private val actions = listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SHOW_TIMERS)

    @Suppress("DEPRECATION")
    fun launch(context: Context, options: Bundle? = null): Boolean {
        val manager = context.packageManager
        val settingsPackage = manager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val excluded = setOfNotNull(context.packageName, "android", settingsPackage)
        fun safe(info: ActivityInfo): Boolean = info.enabled && info.exported && info.packageName !in excluded &&
            info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        fun permitted(info: ActivityInfo): Boolean = info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED
        for (action in actions) {
            val contract = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val preferred = manager.resolveActivity(contract, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            val handlers = manager.queryIntentActivities(contract, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }
                .filter(::safe).sortedByDescending { it.packageName == preferred?.packageName && it.name == preferred?.name }
            // OEM API activities can require SET_ALARM, while their ordinary launcher activity does not.
            val homes = handlers.distinctBy { it.packageName }.mapNotNull { handler ->
                val launch = manager.getLaunchIntentForPackage(handler.packageName) ?: return@mapNotNull null
                if (launch.action != Intent.ACTION_MAIN || !launch.hasCategory(Intent.CATEGORY_LAUNCHER)) return@mapNotNull null
                val info = manager.resolveActivity(launch, 0)?.activityInfo ?: return@mapNotNull null
                if (info.packageName != handler.packageName || !safe(info) || !permitted(info)) return@mapNotNull null
                launch.component = ComponentName(info.packageName, info.name)
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val direct = handlers.filter(::permitted).map { info ->
                Intent(contract).apply { component = ComponentName(info.packageName, info.name) }
            }
            for (target in homes + direct) {
                try {
                    context.startActivity(target, options)
                    return true
                } catch (_: ActivityNotFoundException) {
                    // The app can disappear between resolution and dispatch; try another Clock route.
                } catch (_: SecurityException) {
                    // Skip restricted handlers without requesting another permission.
                }
            }
        }
        Toast.makeText(context, "No system Clock app is available", Toast.LENGTH_SHORT).show()
        return false
    }
}
