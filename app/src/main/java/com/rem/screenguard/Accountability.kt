package com.rem.screenguard

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * v0.19.7 ACCOUNTABILITY RECORD (accountability-report-design-2026-11-11,
 * decisions D1–D7 approved by Alejandro 2026-11-11).
 *
 * A persistent ledger in its OWN prefs file (`sgaccount`) — the
 * codebase's per-concern-file precedent (DebugLog keeps `sgdebug`,
 * Prefs keeps `screenguard`). It records what an accountability
 * partner needs to check: when the app was installed / updated, every
 * accessibility OFF and ON with its blind window, service
 * interruptions (the honest bucket for callback-less deaths), and
 * per-app-per-day block counts. It is NOT the debug log: the
 * "Clear debug log + event log" button clears DebugLog and the Prefs
 * `log` key only and must never touch this file (a grep-level
 * acceptance check when this feature is verified), and there is NO
 * erase path for the ledger in the UI — retention (90 days) is the
 * only eraser; uninstall / clear-data remain visible via the
 * PackageManager install anchors (§3.5 of the design).
 *
 * Detection honesty (design §3): an OFF written by the dying service
 * itself (onUnbind / onDestroy) is EXACT; an ON at onServiceConnected
 * is EXACT; a death with no callback (force-stop, kill, reboot)
 * surfaces as a GAP pair whose OFF edge is the last persisted
 * heartbeat (approximate, at most ~15 s late) and is labelled an
 * interruption, never a settings change. Events store wall-clock AND
 * elapsed-realtime stamps; a window whose two clocks disagree by
 * more than 5 minutes is flagged clock-approximate, and a window
 * spanning a reboot (elapsed clock reset) is reclassified as an
 * interruption — the record says what it knows and labels what it
 * doesn't.
 */
object Accountability {

    private const val FILE = "sgaccount"
    private const val KEY_EVENTS = "events"

    /** A callback-less silence longer than this is an interruption
     *  (eight ~15 s heartbeats — above any scheduling stall, far
     *  below any human off-period that matters). */
    const val GAP_THRESHOLD_MS = 120_000L

    /** Events and day aggregates are kept 90 days (D3) — the longest
     *  report period, so every offered report is fully backed. The
     *  anchors and the RECORD_START event are never swept. */
    const val RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    /** Wall vs elapsed disagreement beyond this flags a window's
     *  duration as clock-approximate. */
    private const val CLOCK_SKEW_MS = 5 * 60_000L

    // Event kinds (ledger lines: wallMs|elapsedMs|KIND|detail).
    const val KIND_OFF = "OFF"
    const val KIND_ON = "ON"
    const val KIND_GAP_OFF = "GAP_OFF"
    const val KIND_GAP_ON = "GAP_ON"
    const val KIND_UPDATE = "UPDATE"
    const val KIND_RECORD_START = "RECORD_START"
    const val KIND_GUARD = "GUARD"

    data class Event(val wall: Long, val elapsed: Long, val kind: String, val detail: String)

    /** A blind (OFF) or interruption (GAP) window, paired from the
     *  event stream. [endWall] null = still open. */
    data class Window(
        val startWall: Long,
        val endWall: Long?,
        val gap: Boolean,
        val duringUpdate: Boolean,
        val clockApprox: Boolean
    ) {
        fun durationMs(now: Long): Long = ((endWall ?: now) - startWall).coerceAtLeast(0)
        fun touchesDay(dayStart: Long, dayEnd: Long, now: Long): Boolean =
            startWall < dayEnd && (endWall ?: now) >= dayStart
    }

    data class Stats(
        val blocks: Int, val warns: Int, val secure: Int, val guards: Int,
        val blindCount: Int, val blindMs: Long, val longestBlindMs: Long,
        val interruptionCount: Int, val interruptionMs: Long,
        val guardedDays: Int, val witnessedDays: Int
    )

    /** A pre-formatted display row (card + report share one wording). */
    data class Item(
        val startWall: Long,
        val endWall: Long?,
        val group: String, // BLIND / INTERRUPTION / UPDATE / START / GUARD
        val line: String
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------- PackageManager anchors ----------------

    /** (firstInstallTime, lastUpdateTime) for our own package — no
     *  permission needed, none added. */
    fun installInfo(ctx: Context): Pair<Long, Long> = try {
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.firstInstallTime to pi.lastUpdateTime
    } catch (e: Exception) {
        0L to 0L
    }

    private fun versionCode(ctx: Context): Long = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    } catch (e: Exception) {
        -1L
    }

    private fun versionName(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** The same Settings.Secure check MainActivity runs — whether
     *  Android currently lists GuardService as enabled. */
    fun isServiceEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, GuardService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    // ---------------- event store ----------------

    fun events(ctx: Context): List<Event> {
        val raw = prefs(ctx).getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size < 3) null
            else Event(
                parts[0].toLongOrNull() ?: return@mapNotNull null,
                parts[1].toLongOrNull() ?: 0L,
                parts[2],
                if (parts.size > 3) parts[3] else ""
            )
        }
    }

    private fun appendEvent(p: SharedPreferences, wall: Long, elapsed: Long, kind: String, detail: String) {
        val old = p.getString(KEY_EVENTS, "") ?: ""
        val line = "$wall|$elapsed|$kind|$detail"
        p.edit().putString(KEY_EVENTS, if (old.isBlank()) line else "$old\n$line").apply()
    }

    private fun mirror(ctx: Context, line: String) {
        // Courtesy copy into the 50-line Event Log (Prefs) — the
        // ledger is the record; the mirror is for casual visibility.
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        Prefs.appendLog(ctx, "$ts Accountability: $line")
        DebugLog.log(ctx, "ACCT $line")
    }

    // ---------------- reconciliation (design §3.4) ----------------

    /**
     * Runs at GuardService.onServiceConnected ([serviceConnecting] =
     * true — that connect IS the exact ON edge) and at
     * MainActivity.onResume (false — the UI can only detect the
     * "ledger says ON, system says OFF, no OFF event" leftover).
     * Bootstraps the ledger on first run, detects updates from the
     * PackageManager anchors, closes open windows, and reconstructs
     * GAP pairs for callback-less deaths.
     */
    @Synchronized
    fun reconcile(ctx: Context, serviceConnecting: Boolean) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val nowEl = SystemClock.elapsedRealtime()
        val (firstInstall, lastUpdate) = installInfo(ctx)
        val vc = versionCode(ctx)
        val e = p.edit()

        if (!p.getBoolean("bootstrapped", false)) {
            // First run of this feature — or its data was cleared.
            // Either way the marker is honest: it carries the
            // PackageManager install date beside the restart.
            appendEvent(p, now, nowEl, KIND_RECORD_START, "install=$firstInstall updated=$lastUpdate")
            e.putLong("anchor_install", firstInstall)
            e.putLong("anchor_update", lastUpdate)
            e.putLong("anchor_vc", vc)
            if (isServiceEnabled(ctx)) {
                e.putString("svc_state", "on")
                e.putLong("on_since_wall", now)
            } else {
                // Off, but no window is opened: the pre-grant /
                // pre-first-connect stretch is setup, not a blind
                // window. off_since stays 0 = untracked.
                e.putString("svc_state", "off")
                e.putLong("off_since_wall", 0L)
            }
            e.putLong("last_alive_wall", now)
            e.putLong("last_alive_elapsed", nowEl)
            e.putBoolean("bootstrapped", true)
            e.commit()
            DebugLog.log(ctx, "ACCT RECORD_START install=$firstInstall updated=$lastUpdate")
            sweep(ctx)
            return
        }

        // UPDATE detection: the anchors moved under a surviving
        // ledger (an uninstall would have wiped the ledger too).
        var updateJustSeen = false
        val storedVc = p.getLong("anchor_vc", -1L)
        val storedUpdate = p.getLong("anchor_update", 0L)
        if (storedVc >= 0 && (vc != storedVc || lastUpdate != storedUpdate)) {
            appendEvent(p, lastUpdate, nowEl, KIND_UPDATE, "vc=$vc name=${versionName(ctx)}")
            updateJustSeen = true
            DebugLog.log(ctx, "ACCT UPDATE vc=$vc")
        }
        if (p.getLong("anchor_install", 0L) != firstInstall) e.putLong("anchor_install", firstInstall)
        e.putLong("anchor_update", lastUpdate)
        e.putLong("anchor_vc", vc)

        val state = p.getString("svc_state", "") ?: ""
        when (state) {
            "on" -> {
                if (serviceConnecting) {
                    // A (re)connect while the ledger says ON: either
                    // an ordinary restart (churn — writes nothing)
                    // or the process died without a callback and the
                    // heartbeat gap proves the silent stretch.
                    val laWall = p.getLong("last_alive_wall", 0L)
                    val laEl = p.getLong("last_alive_elapsed", 0L)
                    if (laWall > 0) {
                        val wallGap = now - laWall
                        val elGap = if (laEl in 1..nowEl) nowEl - laEl else -1L
                        val gap = if (elGap >= 0) elGap else wallGap
                        if (gap > GAP_THRESHOLD_MS) {
                            val clk = elGap >= 0 && abs(wallGap - elGap) > CLOCK_SKEW_MS
                            appendEvent(p, laWall, laEl, KIND_GAP_OFF, "approx=1")
                            appendEvent(p, now, nowEl, KIND_GAP_ON,
                                "dur=$gap" + if (clk) " clk=1" else "")
                            e.putLong("on_since_wall", now)
                            mirror(ctx, "service interruption recorded (${fmtDur(gap)})")
                        }
                    }
                    e.putLong("last_alive_wall", now)
                    e.putLong("last_alive_elapsed", nowEl)
                } else if (!isServiceEnabled(ctx)) {
                    // Ledger ON, system OFF, and no OFF event: the
                    // service died in the narrow window before its
                    // OFF commit could land. Close out as a GAP —
                    // approximate edges bounded by the heartbeat.
                    val laWall = p.getLong("last_alive_wall", 0L)
                    val laEl = p.getLong("last_alive_elapsed", 0L)
                    val start = if (laWall > 0) laWall else now
                    appendEvent(p, start, laEl, KIND_GAP_OFF, "approx=1")
                    e.putString("svc_state", "off")
                    e.putLong("off_since_wall", start)
                    e.putLong("off_since_elapsed", laEl)
                    e.putString("off_kind", KIND_GAP_OFF)
                    mirror(ctx, "accessibility OFF (no clean stop — interruption)")
                }
            }
            "off" -> {
                val offSince = p.getLong("off_since_wall", 0L)
                if (serviceConnecting && offSince <= 0L) {
                    // Bootstrap-off → first connect: the pre-grant
                    // setup stretch was deliberately left untracked
                    // (off_since = 0); this connect simply starts
                    // the ON state — no window opens or closes.
                    e.putString("svc_state", "on")
                    e.putLong("on_since_wall", now)
                    e.putLong("last_alive_wall", now)
                    e.putLong("last_alive_elapsed", nowEl)
                } else if (serviceConnecting && offSince > 0) {
                    // This connect is the exact moment protection
                    // resumed — close the window exactly.
                    val offKind = p.getString("off_kind", KIND_OFF) ?: KIND_OFF
                    val offEl = p.getLong("off_since_elapsed", 0L)
                    // The elapsed clock reset while the window was
                    // open ⇒ the device rebooted inside it. A
                    // settings OFF that spans a reboot cannot be
                    // told apart from the reboot itself — it joins
                    // the interruption bucket (conservative, honest).
                    val rebooted = offEl <= 0L || offEl > nowEl
                    var gap = offKind == KIND_GAP_OFF
                    if (rebooted && !gap) {
                        reclassifyLastOffAsGap(p, offSince)
                        gap = true
                    }
                    val durWall = (now - offSince).coerceAtLeast(0)
                    val durEl = if (!rebooted && offEl > 0) nowEl - offEl else -1L
                    val clk = durEl >= 0 && abs(durWall - durEl) > CLOCK_SKEW_MS
                    val dur = if (durEl >= 0) durEl else durWall
                    // An OFF→ON pair straddling an UPDATE timestamp
                    // is the update's teardown, not the user's
                    // blind time — annotated, never counted.
                    val straddlesUpdate = updateJustSeen ||
                        events(ctx).any { it.kind == KIND_UPDATE && it.wall in offSince..now }
                    val detail = buildString {
                        append("dur=$dur")
                        if (straddlesUpdate) append(" upd=1")
                        if (clk) append(" clk=1")
                        if (rebooted) append(" reb=1")
                    }
                    appendEvent(p, now, nowEl, if (gap) KIND_GAP_ON else KIND_ON, detail)
                    e.putString("svc_state", "on")
                    e.putLong("on_since_wall", now)
                    e.putLong("off_since_wall", 0L)
                    mirror(
                        ctx,
                        if (gap) "service interruption ended (${fmtDur(dur)})"
                        else "accessibility ON — blind ${fmtDur(dur)}"
                    )
                }
            }
        }
        e.commit()
        sweep(ctx)
    }

    /** Rewrite the still-open OFF event as a GAP_OFF (reboot
     *  reclassification at window close). */
    private fun reclassifyLastOffAsGap(p: SharedPreferences, offSince: Long) {
        val evs = eventsFrom(p)
        val idx = evs.indexOfLast { it.kind == KIND_OFF && it.wall == offSince }
        if (idx < 0) return
        val lines = evs.map { ev ->
            val kind = if (ev === evs[idx]) KIND_GAP_OFF else ev.kind
            "${ev.wall}|${ev.elapsed}|$kind|${ev.detail}"
        }
        p.edit().putString(KEY_EVENTS, lines.joinToString("\n")).apply()
    }

    private fun eventsFrom(p: SharedPreferences): List<Event> {
        val raw = p.getString(KEY_EVENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|", limit = 4)
            if (parts.size < 3) null
            else Event(parts[0].toLongOrNull() ?: return@mapNotNull null,
                parts[1].toLongOrNull() ?: 0L, parts[2],
                if (parts.size > 3) parts[3] else "")
        }
    }

    /**
     * The OFF write (design §3.1): called from GuardService.onUnbind
     * AND onDestroy — whichever fires first records it, synchronously
     * (commit), and the persisted state makes the second call a
     * no-op. On the standard settings-toggle path this timestamp is
     * exact: it is written by the dying service itself.
     */
    @Synchronized
    fun recordServiceOff(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("bootstrapped", false)) return
        if (p.getString("svc_state", "") != "on") return // already off — records once
        val now = System.currentTimeMillis()
        val nowEl = SystemClock.elapsedRealtime()
        appendEvent(p, now, nowEl, KIND_OFF, "")
        p.edit()
            .putString("svc_state", "off")
            .putLong("off_since_wall", now)
            .putLong("off_since_elapsed", nowEl)
            .putString("off_kind", KIND_OFF)
            .commit()
        mirror(ctx, "accessibility OFF recorded")
    }

    /** Persist the last-alive proof at the service's ~15 s ALIVE
     *  heartbeat (design §3.3). apply() is fine: the bound it buys is
     *  the heartbeat interval itself. */
    @Synchronized
    fun recordHeartbeat(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("bootstrapped", false)) return
        p.edit()
            .putLong("last_alive_wall", System.currentTimeMillis())
            .putLong("last_alive_elapsed", SystemClock.elapsedRealtime())
            .apply()
    }

    // ---------------- block ledger (design §4) ----------------

    /** One call at executeBlock's counter point — inherits the Home
     *  "Blocks:" counter's semantics exactly (post-refusals,
     *  post-launcher-conversion). Warn-only passes the counter today,
     *  so it is recorded as its own kind, never merged. */
    @Synchronized
    fun recordBlock(ctx: Context, pkg: String, secure: Boolean, enforce: Boolean) {
        val p = prefs(ctx)
        if (!p.getBoolean("bootstrapped", false)) reconcile(ctx, serviceConnecting = true)
        val day = dayKey(System.currentTimeMillis())
        val e = p.edit()
        fun inc(key: String) = e.putInt(key, p.getInt(key, 0) + 1)
        inc("day_total_$day")
        if (enforce) {
            inc("day_blk_$day")
            inc("pkg_blk_$day|$pkg")
            if (secure) {
                inc("day_sec_$day")
                inc("pkg_sec_$day|$pkg")
            }
        } else {
            inc("day_wrn_$day")
            inc("pkg_wrn_$day|$pkg")
        }
        e.apply()
    }

    /** The launcher-guard conversion (design D5): its own kind and
     *  daily count — partner-relevant, never a per-app block. */
    @Synchronized
    fun recordGuard(ctx: Context, pkg: String) {
        val p = prefs(ctx)
        if (!p.getBoolean("bootstrapped", false)) reconcile(ctx, serviceConnecting = true)
        val now = System.currentTimeMillis()
        appendEvent(p, now, SystemClock.elapsedRealtime(), KIND_GUARD, pkg)
        val day = dayKey(now)
        p.edit().putInt("day_guard_$day", p.getInt("day_guard_$day", 0) + 1).apply()
    }

    // ---------------- retention (design §5.1) ----------------

    /** 90-day sweep of events + day aggregates, at service start and
     *  report generation. RECORD_START events and the anchors are
     *  never swept. */
    @Synchronized
    fun sweep(ctx: Context) {
        val p = prefs(ctx)
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val evs = events(ctx)
        val kept = evs.filter { it.wall >= cutoff || it.kind == KIND_RECORD_START }
        val e = p.edit()
        if (kept.size != evs.size) {
            e.putString(KEY_EVENTS, kept.joinToString("\n") {
                "${it.wall}|${it.elapsed}|${it.kind}|${it.detail}"
            })
        }
        val dayCutoff = dayKey(cutoff)
        val dateRe = Regex("\\d{8}")
        for (key in p.all.keys) {
            if (key.startsWith("day_") || key.startsWith("pkg_")) {
                val d = dateRe.find(key)?.value ?: continue
                if (d < dayCutoff) e.remove(key)
            }
        }
        e.apply()
    }

    // ---------------- reads: state, windows, stats ----------------

    fun svcState(ctx: Context): String = prefs(ctx).getString("svc_state", "") ?: ""
    fun offSinceWall(ctx: Context): Long = prefs(ctx).getLong("off_since_wall", 0L)
    fun onSinceWall(ctx: Context): Long = prefs(ctx).getLong("on_since_wall", 0L)

    /** Wall time the record began (first RECORD_START), falling back
     *  to the PackageManager install time. */
    fun recordStartWall(ctx: Context): Long =
        events(ctx).firstOrNull { it.kind == KIND_RECORD_START }?.wall
            ?: installInfo(ctx).first

    /** All windows (blind + interruption), paired from the stream. */
    fun windows(ctx: Context): List<Window> {
        val out = mutableListOf<Window>()
        var open: Window? = null
        for (ev in events(ctx)) {
            when (ev.kind) {
                KIND_OFF -> if (open == null) open = Window(ev.wall, null, gap = false, duringUpdate = false, clockApprox = false)
                KIND_GAP_OFF -> if (open == null) open = Window(ev.wall, null, gap = true, duringUpdate = false, clockApprox = false)
                KIND_ON, KIND_GAP_ON -> {
                    val o = open
                    if (o != null) {
                        out += o.copy(
                            endWall = ev.wall,
                            duringUpdate = ev.detail.contains("upd=1"),
                            clockApprox = ev.detail.contains("clk=1")
                        )
                        open = null
                    }
                }
            }
        }
        if (open != null) out += open
        return out
    }

    fun stats(ctx: Context, fromWall: Long, toWall: Long): Stats {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        var blocks = 0; var warns = 0; var secure = 0; var guards = 0
        val cal = Calendar.getInstance()
        cal.timeInMillis = fromWall
        setDayStart(cal)
        val lastDay = dayKey(toWall)
        while (true) {
            val dk = dayKey(cal.timeInMillis)
            blocks += p.getInt("day_blk_$dk", 0)
            warns += p.getInt("day_wrn_$dk", 0)
            secure += p.getInt("day_sec_$dk", 0)
            guards += p.getInt("day_guard_$dk", 0)
            if (dk >= lastDay) break
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        var blindCount = 0; var blindMs = 0L; var longest = 0L
        var intCount = 0; var intMs = 0L
        val wins = windows(ctx)
        for (w in wins) {
            val end = w.endWall ?: now
            if (end < fromWall || w.startWall > toWall) continue
            val overlap = minOf(end, toWall) - maxOf(w.startWall, fromWall)
            if (overlap < 0) continue
            when {
                w.gap -> { intCount++; intMs += overlap }
                w.duringUpdate -> { /* annotated in the list; never counted as user blind time */ }
                else -> {
                    blindCount++; blindMs += overlap
                    val full = w.durationMs(now)
                    if (full > longest) longest = full
                }
            }
        }
        // Fully-guarded days: witnessed days (record start → period
        // end) no blind/interruption window touches. Update-straddle
        // windows are platform-caused and annotated in the list;
        // they do not break a day.
        val recStart = recordStartWall(ctx)
        val firstWitnessed = maxOf(dayStartOf(fromWall), dayStartOf(recStart))
        var witnessed = 0; var guarded = 0
        cal.timeInMillis = firstWitnessed
        while (cal.timeInMillis <= toWall) {
            val ds = dayStartOf(cal.timeInMillis)
            val de = ds + 24L * 60 * 60 * 1000
            witnessed++
            val broken = wins.any { w ->
                !w.duringUpdate && w.touchesDay(ds, de, now)
            }
            if (!broken) guarded++
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return Stats(blocks, warns, secure, guards, blindCount, blindMs, longest,
            intCount, intMs, guarded, witnessed)
    }

    /** Per-app (blocks, warns, secureBlocks) over a day range. */
    fun perApp(ctx: Context, fromWall: Long, toWall: Long): Map<String, IntArray> {
        val p = prefs(ctx)
        val fromDay = dayKey(fromWall)
        val toDay = dayKey(toWall)
        val out = mutableMapOf<String, IntArray>()
        for ((key, value) in p.all) {
            if (value !is Int) continue
            val kindIdx = when {
                key.startsWith("pkg_blk_") -> 0
                key.startsWith("pkg_wrn_") -> 1
                key.startsWith("pkg_sec_") -> 2
                else -> continue
            }
            val rest = key.substring(8) // yyyyMMdd|<pkg>
            if (rest.length < 10) continue
            val day = rest.substring(0, 8)
            if (day < fromDay || day > toDay) continue
            val pkg = rest.substring(9)
            val arr = out.getOrPut(pkg) { IntArray(3) }
            arr[kindIdx] += value
        }
        return out
    }

    fun blocksOn(ctx: Context, day: String): Int = prefs(ctx).getInt("day_blk_$day", 0)

    // ---------------- display ----------------

    /** Chronological, windows combined — one wording for the card
     *  and the report (design §6/§7). */
    fun displayItems(ctx: Context): List<Item> {
        val out = mutableListOf<Item>()
        val now = System.currentTimeMillis()
        var openKind: String? = null
        var openWall = 0L
        for (ev in events(ctx)) {
            when (ev.kind) {
                KIND_RECORD_START -> {
                    val inst = ev.detail.substringAfter("install=", "0").substringBefore(" ")
                        .toLongOrNull() ?: 0L
                    out += Item(ev.wall, null, "START",
                        "Accountability record begins · ${fmtDate(ev.wall)}" +
                            if (inst > 0) " (app installed ${fmtDate(inst)})" else "")
                }
                KIND_UPDATE -> {
                    val name = ev.detail.substringAfter("name=", "")
                    out += Item(ev.wall, null, "UPDATE",
                        "App updated${if (name.isNotBlank()) " to $name" else ""} · ${fmtDateTime(ev.wall)}")
                }
                KIND_GUARD -> out += Item(ev.wall, null, "GUARD",
                    "Launcher guard — content in app previews · ${fmtDateTime(ev.wall)}")
                KIND_OFF, KIND_GAP_OFF -> {
                    if (openKind == null) { openKind = ev.kind; openWall = ev.wall }
                }
                KIND_ON, KIND_GAP_ON -> {
                    val gap = (openKind == KIND_GAP_OFF) || ev.kind == KIND_GAP_ON
                    val start = if (openKind != null) openWall else ev.wall
                    val dur = detailLong(ev.detail, "dur") ?: (ev.wall - start).coerceAtLeast(0)
                    val line = if (gap) {
                        "Service interrupted ${fmtDateTime(start)} → ${fmtTime(ev.wall)} " +
                            "(${fmtDur(dur)} — restart, update or system kill, not a settings change)"
                    } else {
                        buildString {
                            append("OFF ${fmtDateTime(start)} → ON ${fmtTime(ev.wall)} (blind ${fmtDur(dur)})")
                            if (ev.detail.contains("upd=1")) append(" · during app update")
                            if (ev.detail.contains("clk=1")) append(" · clock changed — duration approximate")
                        }
                    }
                    out += Item(start, ev.wall, if (gap) "INTERRUPTION" else "BLIND", line)
                    openKind = null
                }
            }
        }
        if (openKind != null) {
            val gap = openKind == KIND_GAP_OFF
            val soFar = (now - openWall).coerceAtLeast(0)
            out += Item(openWall, null, if (gap) "INTERRUPTION" else "BLIND",
                if (gap) "Service interrupted since ${fmtDateTime(openWall)} — still off (${fmtDur(soFar)} so far)"
                else "OFF since ${fmtDateTime(openWall)} — still off, protection is blind (${fmtDur(soFar)} so far)")
        }
        return out
    }

    // ---------------- formatting ----------------

    fun dayKey(wall: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(wall))

    fun dayStartOf(wall: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = wall
        setDayStart(cal)
        return cal.timeInMillis
    }

    private fun setDayStart(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    fun fmtDate(wall: Long): String =
        SimpleDateFormat("EEE MMM d, yyyy", Locale.US).format(Date(wall))

    fun fmtDateTime(wall: Long): String =
        SimpleDateFormat("EEE MMM d, HH:mm", Locale.US).format(Date(wall))

    fun fmtTime(wall: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(wall))

    fun fmtDur(ms: Long): String {
        val sec = ms / 1000
        if (sec < 60) return "<1 min"
        val min = sec / 60
        if (min < 60) return "$min min"
        val h = min / 60
        val m = min % 60
        return if (m == 0L) "$h h" else "$h h ${m.toString().padStart(2, '0')} min"
    }

    private fun detailLong(detail: String, key: String): Long? =
        detail.split(" ").firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.toLongOrNull()
}
