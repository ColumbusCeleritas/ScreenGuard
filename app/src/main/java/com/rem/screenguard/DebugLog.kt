package com.rem.screenguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Verbose PoC debug log: a ring buffer of timestamped one-liners kept in its own
 * prefs file, viewable and shareable from the UI. This is what tells us WHY the
 * guard did or did not analyse a frame (capture failures, diff skips, scores).
 * Contains only timestamps, package names, counters and scores — never images.
 */
object DebugLog {
    private const val FILE = "sgdebug"
    private const val KEY = "lines"
    private const val MAX = 6000 // v0.2.1: a 5-min field test overflows 1500

    fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    @Synchronized
    fun log(ctx: Context, line: String) {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val old = p.getString(KEY, "") ?: ""
        val lines = (old.split("\n").filter { it.isNotBlank() } + (ts() + " " + line)).takeLast(MAX)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun text(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
