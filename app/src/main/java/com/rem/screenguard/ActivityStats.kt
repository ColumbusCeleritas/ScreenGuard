package com.rem.screenguard

import android.content.Context
import android.content.SharedPreferences

/**
 * v0.19.8 ACTIVITY & BATTERY STATS (menu-battery-sus-design-2026-11-14,
 * workstream 2, approved by Alejandro 2026-11-14).
 *
 * A daily-counters ledger in its OWN prefs file (`sgstats`) — the
 * codebase's per-concern-file precedent (Prefs keeps `screenguard`,
 * DebugLog keeps `sgdebug`, Accountability keeps `sgaccount`). It
 * counts the guard's own work per local day, because the work is
 * what spends the battery: captures attempted / ok / failed,
 * analyses run and their total model time, SUS-mode time, and
 * blocks (enforced and warn-only separately). Android's own per-app
 * battery estimate is NOT readable by the app itself — that number
 * lives in Settings → Battery, and the stats screen says so.
 *
 * Flush discipline: counters accumulate in memory only and are
 * written at the service's existing ~15 s ALIVE heartbeat and on
 * service destroy — never per capture. Capture counts are
 * delta-sampled from the guard's cumulative Prefs counters
 * (cap_try / cap_ok / cap_fail), so no capture site changes.
 * SUS time is sampled at flush: if SUS was live at the previous
 * sample, the interval since is added (transition error ≤ one
 * heartbeat, stated on the screen). A flush attributes its counts
 * to the day it lands in; the first write of a new day IS the
 * rollover. Retention: 30 days of rows, pruned at flush.
 */
object ActivityStats {

    private const val FILE = "sgstats"
    private const val RETENTION_DAYS = 30

    data class DayRow(
        val dayKey: String,
        val capTry: Int, val capOk: Int, val capFail: Int,
        val analyses: Int, val analysisMs: Long,
        val susMs: Long,
        val blocks: Int, val warns: Int
    )

    // ---- in-memory accumulators (flushed, never per-event I/O) ----
    private var pendAnalyses = 0
    private var pendAnalysisMs = 0L
    private var pendBlocks = 0
    private var pendWarns = 0
    // Last sampled values of the cumulative Prefs capture counters;
    // -1 = not yet sampled (the first flush only sets the baseline,
    // so a service restart never invents a day's captures).
    private var lastCapTry = -1
    private var lastCapOk = -1
    private var lastCapFail = -1
    // SUS sampling state.
    private var lastSampleMs = 0L
    private var lastSusLive = false

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** One completed analysis (the three `checks++` completion
     *  points in GuardService). ms = FAL total + CLS total, the
     *  same sum the stack path persists as last_latency_ms. */
    @Synchronized
    fun noteAnalysis(ms: Long) {
        pendAnalyses++
        if (ms > 0) pendAnalysisMs += ms
    }

    /** One block at executeBlock's counter point — the same
     *  post-refusal, post-launcher-conversion point the
     *  accountability ledger counts from, so the two records can
     *  never disagree about what a block was. */
    @Synchronized
    fun noteBlock(enforce: Boolean) {
        if (enforce) pendBlocks++ else pendWarns++
    }

    /**
     * Fold everything pending into today's row. Called from the
     * ~15 s ALIVE heartbeat (with the live SUS test) and from
     * GuardService.onDestroy. [susLive] = any SUS-class window is
     * live at this sample (GuardService.anySusLive).
     */
    @Synchronized
    fun flush(ctx: Context, susLive: Boolean) {
        val now = System.currentTimeMillis()
        // SUS time: the interval since the last sample counts when
        // SUS was live at that sample (sampled, ≤ ~15 s error at
        // each transition — stated on the screen).
        var susDelta = 0L
        if (lastSampleMs > 0L && lastSusLive) {
            susDelta = (now - lastSampleMs).coerceAtLeast(0)
        }
        lastSampleMs = now
        lastSusLive = susLive

        // Capture deltas from the cumulative counters.
        val p = Prefs.get(ctx)
        val cTry = p.getInt("cap_try", 0)
        val cOk = p.getInt("cap_ok", 0)
        val cFail = p.getInt("cap_fail", 0)
        var dTry = 0; var dOk = 0; var dFail = 0
        if (lastCapTry >= 0) {
            dTry = (cTry - lastCapTry).coerceAtLeast(0)
            dOk = (cOk - lastCapOk).coerceAtLeast(0)
            dFail = (cFail - lastCapFail).coerceAtLeast(0)
        }
        lastCapTry = cTry; lastCapOk = cOk; lastCapFail = cFail

        if (dTry == 0 && dOk == 0 && dFail == 0 && susDelta == 0L &&
            pendAnalyses == 0 && pendBlocks == 0 && pendWarns == 0
        ) return

        val sp = prefs(ctx)
        val day = Accountability.dayKey(now)
        val e = sp.edit()
        fun addI(suffix: String, delta: Int) {
            if (delta != 0) e.putInt("d${day}_$suffix", sp.getInt("d${day}_$suffix", 0) + delta)
        }
        fun addL(suffix: String, delta: Long) {
            if (delta != 0L) e.putLong("d${day}_$suffix", sp.getLong("d${day}_$suffix", 0L) + delta)
        }
        addI("try", dTry); addI("ok", dOk); addI("fail", dFail)
        addI("an", pendAnalyses); addL("ams", pendAnalysisMs)
        addL("sus", susDelta)
        addI("blk", pendBlocks); addI("wrn", pendWarns)
        pendAnalyses = 0; pendAnalysisMs = 0L; pendBlocks = 0; pendWarns = 0
        // Retention: prune rows older than 30 days.
        val cutoff = Accountability.dayKey(now - RETENTION_DAYS * 24L * 60 * 60 * 1000)
        for (key in sp.all.keys) {
            if (key.length > 9 && key.startsWith("d")) {
                val dk = key.substring(1, 9)
                if (dk.all { it.isDigit() } && dk < cutoff) e.remove(key)
            }
        }
        e.apply()
    }

    /** Today + the previous 7 days, newest first — rows exist only
     *  for days the guard actually recorded work in. */
    fun recentDays(ctx: Context): List<DayRow> {
        val sp = prefs(ctx)
        val out = mutableListOf<DayRow>()
        val cal = java.util.Calendar.getInstance()
        for (i in 0 until 8) {
            val dk = Accountability.dayKey(cal.timeInMillis)
            if (sp.contains("d${dk}_try") || sp.contains("d${dk}_an") || sp.contains("d${dk}_sus")) {
                out += DayRow(
                    dk,
                    sp.getInt("d${dk}_try", 0), sp.getInt("d${dk}_ok", 0),
                    sp.getInt("d${dk}_fail", 0),
                    sp.getInt("d${dk}_an", 0), sp.getLong("d${dk}_ams", 0L),
                    sp.getLong("d${dk}_sus", 0L),
                    sp.getInt("d${dk}_blk", 0), sp.getInt("d${dk}_wrn", 0)
                )
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    /** "Wed Nov 12, 2026" for a yyyyMMdd day key (report formats). */
    fun dayLabel(dayKey: String): String {
        val cal = java.util.Calendar.getInstance()
        return try {
            val y = dayKey.substring(0, 4).toInt()
            val m = dayKey.substring(4, 6).toInt() - 1
            val d = dayKey.substring(6, 8).toInt()
            cal.set(y, m, d)
            Accountability.fmtDate(cal.timeInMillis)
        } catch (e: Exception) {
            dayKey
        }
    }

    /** Compact duration for the stats rows: "12 s", "3 min",
     *  "1 h 04 min", "2 h". Mirrors Accountability.fmtDur's shape
     *  but keeps seconds (a quiet day's SUS time is seconds). */
    fun fmtDurShort(ms: Long): String {
        val sec = ms / 1000
        if (sec < 60) return "$sec s"
        val min = sec / 60
        if (min < 60) return "$min min"
        val h = min / 60
        val m = min % 60
        return if (m == 0L) "$h h" else "$h h ${m.toString().padStart(2, '0')} min"
    }
}
