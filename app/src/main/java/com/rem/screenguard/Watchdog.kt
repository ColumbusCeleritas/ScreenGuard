package com.rem.screenguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * v0.19.12 SILENT-DEATH WATCHDOG (design:
 * hidden_files/silent-death-watchdog-design-2026-11-21.md, Option A —
 * approved by Alejandro 2026-11-23; root cause:
 * hidden_files/vc91-root-cause-audit.md).
 *
 * The defect it closes: OxygenOS can kill the accessibility guard
 * process without either OFF callback (a GAP death in the ledger).
 * Android is not obliged to rebind, and vc90 had NO machinery outside
 * the dying process that could notice — the Nov 19→21 blind window
 * ran 40 h 18 min with zero user-facing signal.
 *
 * Shape: a system-scheduled dead-man's check. WorkManager runs the
 * evaluator every 15 min (its floor), a BOOT_COMPLETED receiver and
 * every MainActivity open run it too. The evaluator reads ONLY the
 * accountability ledger's existing liveness state (svc_state +
 * last_alive_wall / last_alive_elapsed in sgaccount) — no new
 * heartbeat store, no change to the ledger's arithmetic, no change
 * to GuardService at all.
 *
 * Semantics (the design's rules):
 *  - Service disabled in Settings (svc_state "off", or the system no
 *    longer lists it) → SILENT. That state is the user's own act,
 *    already visible, and the ledger records it.
 *  - Enabled + heartbeat fresh (≤ STALE_MS) → ALIVE; any posted
 *    alert is cancelled.
 *  - Enabled + heartbeat stale → post a high-importance alert
 *    deep-linking to the Accessibility settings page (the only
 *    revival screen there is — an accessibility service cannot
 *    rebind itself). Re-alert at most once per REALERT_MS while the
 *    window stays open.
 *  - Phone-off time never counts as blind: ages are computed on the
 *    elapsed-realtime clock (which excludes power-off), and a
 *    heartbeat stamped by a previous boot (elapsed clock reset)
 *    ages from THIS boot's uptime instead — a service that fails to
 *    rebind after a reboot alerts only once the phone has been up
 *    STALE_MS with no heartbeat.
 *
 * Failure isolation: every entry point swallows Throwable. A
 * watchdog failure must never take the guard, the UI, or the boot
 * sequence down with it; the worst case is the pre-vc91 behaviour
 * (silence), never a new crash.
 */
object Watchdog {

    /** Heartbeat silence beyond this, while enabled, is a death. */
    const val STALE_MS = 30L * 60_000L

    /** While a death window stays open, re-alert at most this often. */
    const val REALERT_MS = 2L * 60 * 60_000L

    private const val FILE = "sgwatchdog"
    private const val CHANNEL_ID = "sg_watchdog"
    private const val NOTIFICATION_ID = 1202
    private const val WORK_NAME = "sg_watchdog_periodic"

    enum class State { NEVER_ON, OFF, ALIVE, SILENT }

    data class Verdict(val state: State, val silenceMs: Long)

    /** Pure read of the ledger's liveness state — the same
     *  dual-clock rules Accountability.reconcile uses. */
    fun evaluate(ctx: Context): Verdict {
        val p = Accountability.prefs(ctx)
        if (!p.getBoolean("bootstrapped", false)) return Verdict(State.NEVER_ON, 0L)
        if (p.getString("svc_state", "") != "on") return Verdict(State.OFF, 0L)
        if (!Accountability.isServiceEnabled(ctx)) return Verdict(State.OFF, 0L)
        val laWall = p.getLong("last_alive_wall", 0L)
        val laEl = p.getLong("last_alive_elapsed", 0L)
        if (laWall <= 0L) return Verdict(State.NEVER_ON, 0L)
        val nowEl = SystemClock.elapsedRealtime()
        // Elapsed age within this boot; a stamp from a previous boot
        // (elapsed clock reset: laEl > nowEl, or no stamp) ages from
        // this boot's uptime, so power-off time is never blamed.
        val silence = if (laEl in 1..nowEl) nowEl - laEl else nowEl
        return if (silence > STALE_MS) Verdict(State.SILENT, silence)
        else Verdict(State.ALIVE, silence)
    }

    /**
     * Evaluate and act: post / re-post / cancel the alert and write
     * the WATCHDOG DebugLog line on every outcome CHANGE (the metric
     * sheet's field evidence). [source] = worker | boot | open.
     * Never throws.
     */
    fun evaluateAndAct(ctx: Context, source: String) {
        try {
            val v = evaluate(ctx)
            val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val prev = prefs.getString("last_state", "") ?: ""
            val now = System.currentTimeMillis()
            when (v.state) {
                State.SILENT -> {
                    val lastAlert = prefs.getLong("last_alert_wall", 0L)
                    if (prev != State.SILENT.name || now - lastAlert >= REALERT_MS) {
                        postAlert(ctx, v.silenceMs)
                        prefs.edit().putLong("last_alert_wall", now).apply()
                        DebugLog.log(
                            ctx,
                            "WATCHDOG alert posted src=$source silence=${v.silenceMs / 60_000}min"
                        )
                    }
                }
                State.ALIVE -> {
                    if (prev == State.SILENT.name) {
                        cancelAlert(ctx)
                        DebugLog.log(ctx, "WATCHDOG alive src=$source — alert cancelled")
                    }
                    prefs.edit().putLong("last_alert_wall", 0L).apply()
                }
                State.OFF, State.NEVER_ON -> {
                    if (prev == State.SILENT.name) {
                        cancelAlert(ctx)
                        DebugLog.log(ctx, "WATCHDOG off src=$source — alert cancelled")
                    }
                    prefs.edit().putLong("last_alert_wall", 0L).apply()
                }
            }
            if (prev != v.state.name) {
                if (v.state != State.SILENT && !(prev == State.SILENT.name && v.state == State.ALIVE)) {
                    DebugLog.log(ctx, "WATCHDOG state ${prev.ifBlank { "-" }} -> ${v.state.name} src=$source")
                }
                prefs.edit().putString("last_state", v.state.name).apply()
            }
        } catch (t: Throwable) {
            // Failure isolation: a watchdog error is logged if the
            // log itself still works, and never propagated.
            try {
                DebugLog.log(ctx, "WATCHDOG eval failed src=$source ${t.javaClass.simpleName}: ${t.message}")
            } catch (_: Throwable) {
            }
        }
    }

    private fun postAlert(ctx: Context, silenceMs: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Protection alerts", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Warns when the Castitas guard service has stopped."
                }
            )
        }
        val open = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mins = (silenceMs / 60_000L).coerceAtLeast(1)
        val notif = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Protection is off")
            .setContentText(
                "Castitas stopped $mins min ago — the guard service is no longer running. " +
                    "Tap to restore protection."
            )
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Castitas stopped $mins min ago — the guard service is no longer running. " +
                        "Tap to open Accessibility settings and switch Castitas off and back on. " +
                        "Setting its battery use to Unrestricted helps prevent this."
                )
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notif)
    }

    private fun cancelAlert(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    /** Arm the 15-min periodic check. Idempotent (KEEP), safe to
     *  call from every entry point. Never throws. */
    fun schedule(ctx: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        } catch (t: Throwable) {
            try {
                DebugLog.log(ctx, "WATCHDOG schedule failed ${t.javaClass.simpleName}: ${t.message}")
            } catch (_: Throwable) {
            }
        }
    }
}

/** The WorkManager entry point — deliberately a thin shell over
 *  [Watchdog.evaluateAndAct] so the worker, the boot receiver and
 *  the on-open check all share one code path. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Watchdog.evaluateAndAct(applicationContext, "worker")
        return Result.success()
    }
}
