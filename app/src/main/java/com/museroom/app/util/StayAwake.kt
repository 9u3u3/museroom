package com.museroom.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Asking Android not to put Museroom to sleep.
 *
 * Detection lives in a service the system keeps running, which is enough on a
 * stock phone. It is not enough on the many phones that stop background apps
 * anyway to save battery: the music plays, nothing is counted, and the person
 * has no way of telling that anything went wrong. So the exemption is asked
 * for, once, and its state is shown rather than assumed.
 */
object StayAwake {

    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The direct request, which shows one dialog.
     *
     * Falls back to the list of all apps, because a few manufacturers remove
     * the direct dialog and the alternative to a longer path is no path.
     */
    fun ask(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val listed = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(listed) }
    }
}
