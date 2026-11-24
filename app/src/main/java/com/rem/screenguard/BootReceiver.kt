package com.rem.screenguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v0.19.12: a reboot is exactly when a killed accessibility service
 * stays dead (OxygenOS does not reliably rebind it). Re-arm the
 * watchdog's periodic work and run one immediate evaluation — the
 * evaluator's boot-aware ageing (Watchdog.evaluate) keeps this from
 * alerting on the boot itself: a failed rebind only alerts once the
 * phone has been up past the staleness threshold with no heartbeat.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Watchdog.schedule(context)
        Watchdog.evaluateAndAct(context, "boot")
    }
}
