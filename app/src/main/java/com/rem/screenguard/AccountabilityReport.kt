package com.rem.screenguard

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v0.19.7: the shareable accountability report
 * (accountability-report-design-2026-11-11, §7 / D2 / D6).
 *
 * The app has no Compose — the report is drawn directly with
 * Canvas + Paint onto a 1080 px-wide Bitmap, the ScoreGraphActivity
 * precedent, in the app's palette. It leaves the phone only through
 * the user's own share-sheet choice: the PNG is inserted into
 * MediaStore Images (Pictures/ScreenGuard) and shared with
 * ACTION_SEND — LogExport's MediaStore route, mirrored. No
 * FileProvider, no manifest change, no new permission, and the app
 * still has no INTERNET permission: it sends nothing itself.
 *
 * Calendar honesty (§7.3): days before the record began are greyed,
 * never shown as clean — a clean-looking day the app never witnessed
 * would be a lie by layout.
 */
object AccountabilityReport {

    private const val W = 1080
    private const val PAD = 48f
    private const val CW = W - 2 * PAD // content width = 984

    /** Entry from the Home card: render off the main thread, then
     *  insert + share on the UI thread. Never fails silently. */
    fun shareReport(activity: Activity, days: Int) {
        Thread {
            val bmp = try {
                render(activity.applicationContext, days)
            } catch (t: Throwable) {
                null
            }
            activity.runOnUiThread {
                if (bmp == null) {
                    Toast.makeText(activity, "Report generation FAILED", Toast.LENGTH_LONG).show()
                    DebugLog.log(activity, "ACCT REPORT render FAILED")
                } else {
                    insertAndShare(activity, bmp, days)
                }
            }
        }.start()
    }

    private fun insertAndShare(activity: Activity, bmp: Bitmap, days: Int) {
        val name = "screenguard-report-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
        val uri = try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "ScreenGuard"
                )
            }
            val u = activity.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (u != null) {
                activity.contentResolver.openOutputStream(u)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return fail(activity)
            }
            u
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            fail(activity)
            return
        }
        Toast.makeText(activity, "Saved to Pictures/ScreenGuard/$name", Toast.LENGTH_LONG).show()
        DebugLog.log(activity, "ACCT REPORT ok $name (${days}d)")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_SUBJECT, "ScreenGuard accountability report")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Share accountability report"))
    }

    private fun fail(activity: Activity) {
        Toast.makeText(activity, "Report export FAILED", Toast.LENGTH_LONG).show()
        DebugLog.log(activity, "ACCT REPORT FAILED (MediaStore)")
    }

    // ---------------- rendering ----------------

    /** Two-pass: measure with a null canvas, then draw for real. */
    fun render(ctx: Context, days: Int): Bitmap {
        Accountability.sweep(ctx)
        val sheet = Sheet(ctx, days)
        val height = sheet.layout(null).toInt().coerceIn(400, 16000)
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        sheet.layout(Canvas(bmp))
        return bmp
    }

    private class Sheet(val ctx: Context, val days: Int) {
        val p = SgUi.palette(ctx)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = 0f

        val now: Long = System.currentTimeMillis()
        val toDayStart: Long = Accountability.dayStartOf(now)
        val fromDayStart: Long = toDayStart - (days - 1) * DAY_MS
        val stats = Accountability.stats(ctx, fromDayStart, now)
        val windows = Accountability.windows(ctx)
        val recordStartDay: Long = Accountability.dayStartOf(Accountability.recordStartWall(ctx))

        fun layout(canvas: Canvas?): Float {
            y = 56f
            // ---- header ----
            text(canvas, "ScreenGuard — Accountability report", 44f, p.text, bold = true)
            y += 14f
            val (installed, updated) = Accountability.installInfo(ctx)
            val ver = try {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (e: Exception) { "" }
            text(
                canvas,
                "Generated ${Accountability.fmtDateTime(now)} · App v$ver",
                27f, p.muted
            )
            text(
                canvas,
                buildString {
                    append("Installed: ${Accountability.fmtDate(installed)}")
                    if (updated > installed) append(" · Updated: ${Accountability.fmtDate(updated)}")
                    append(" · ${Build.MODEL}")
                },
                27f, p.muted
            )
            text(
                canvas,
                "Period: ${Accountability.fmtDate(fromDayStart)} – ${Accountability.fmtDate(now)} ($days days)",
                27f, p.muted
            )
            divider(canvas)
            // ---- summary ----
            section(canvas, "SUMMARY")
            text(canvas, "Days fully guarded: ${stats.guardedDays} of ${stats.witnessedDays}", 30f, p.text)
            text(
                canvas,
                "Blocks: ${stats.blocks} (${stats.secure} incognito) · Warnings: ${stats.warns}",
                30f, p.text
            )
            text(canvas, "Launcher-guard events: ${stats.guards}", 30f, p.text)
            text(
                canvas,
                if (stats.blindCount == 0) "Accessibility turned off: 0 times"
                else "Accessibility turned off: ${stats.blindCount} times · blind " +
                    "${Accountability.fmtDur(stats.blindMs)} total · longest ${Accountability.fmtDur(stats.longestBlindMs)}",
                30f, p.text
            )
            text(
                canvas,
                if (stats.interruptionCount == 0) "Service interruptions (restarts): 0"
                else "Service interruptions (restarts): ${stats.interruptionCount} · ${Accountability.fmtDur(stats.interruptionMs)}",
                30f, p.text
            )
            divider(canvas)
            // ---- calendar ----
            section(canvas, "CALENDAR")
            calendar(canvas)
            y += 10f
            text(
                canvas,
                "Cell number = blocks that day. Ring (top right of a cell) = a blind window touched " +
                    "that day · half dot (bottom right) = a service interruption · grey = before the " +
                    "record began — not witnessed, not shown as clean.",
                24f, p.muted
            )
            divider(canvas)
            // ---- by app ----
            section(canvas, "BY APP")
            val perApp = Accountability.perApp(ctx, fromDayStart, now)
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, IntArray>> { it.value[0] + it.value[1] })
            if (perApp.isEmpty()) {
                text(canvas, "No blocks or warnings in this period.", 28f, p.muted)
            } else {
                for ((pkg, counts) in perApp) {
                    val label = appLabel(pkg)
                    val parts = mutableListOf<String>()
                    if (counts[0] > 0) parts += "${counts[0]} blocks" +
                        if (counts[2] > 0) " (${counts[2]} incognito)" else ""
                    if (counts[1] > 0) parts += "${counts[1]} warnings"
                    text(canvas, "$label — ${parts.joinToString(" · ")}", 28f, p.text)
                }
            }
            divider(canvas)
            // ---- events ----
            section(canvas, "EVENTS")
            val items = Accountability.displayItems(ctx).filter {
                (it.endWall ?: it.startWall) >= fromDayStart && it.startWall <= now
            }
            if (items.isEmpty()) {
                text(canvas, "No accessibility events in this period.", 28f, p.muted)
            } else {
                for (item in items) text(canvas, item.line, 26f, p.text)
            }
            divider(canvas)
            // ---- footer ----
            text(
                canvas,
                "Made on this phone by ScreenGuard. It records what the app could see: accessibility " +
                    "off/on, blocks and installs. Time with the phone off, or the app removed, cannot " +
                    "be observed — the install date above is the anchor.",
                24f, p.muted
            )
            y += 40f
            return y
        }

        private fun calendar(canvas: Canvas?) {
            val gap = 8f
            val cellW = (CW - 6 * gap) / 7f
            val cellH = 100f
            // Weekday header (locale first day of week first).
            val firstDow = Calendar.getInstance().firstDayOfWeek
            val dayNames = arrayOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
            paint.textSize = 24f
            paint.color = p.muted
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textAlign = Paint.Align.CENTER
            for (col in 0..6) {
                val dow = (firstDow - 1 + col) % 7 // Calendar.SUNDAY == 1
                if (canvas != null) {
                    canvas.drawText(
                        dayNames[dow], PAD + col * (cellW + gap) + cellW / 2f, y + 26f, paint
                    )
                }
            }
            paint.textAlign = Paint.Align.LEFT
            y += 40f
            // First cell: the first-day-of-week on/before the period start.
            val cal = Calendar.getInstance()
            cal.timeInMillis = fromDayStart
            while (cal.get(Calendar.DAY_OF_WEEK) != firstDow) cal.add(Calendar.DAY_OF_YEAR, -1)
            val gridStart = cal.timeInMillis
            var week = 0
            while (true) {
                val rowStart = gridStart + week * 7 * DAY_MS
                if (rowStart > toDayStart) break
                for (col in 0..6) {
                    val dayStart = rowStart + col * DAY_MS
                    if (dayStart < fromDayStart || dayStart > toDayStart) continue
                    drawCell(canvas, PAD + col * (cellW + gap), y, cellW, cellH, dayStart)
                }
                y += cellH + gap
                week++
            }
        }

        private fun drawCell(canvas: Canvas?, x: Float, top: Float, w: Float, h: Float, dayStart: Long) {
            val greyed = dayStart < recordStartDay
            val blocks = Accountability.blocksOn(ctx, Accountability.dayKey(dayStart))
            val fill = when {
                greyed -> blend(p.bg, p.surface2, 0.5f)
                blocks == 0 -> p.surface2
                blocks <= 2 -> blend(p.surface2, p.accent, 0.35f)
                blocks <= 5 -> blend(p.surface2, p.accent, 0.62f)
                else -> p.accent
            }
            val onAccent = !greyed && blocks >= 6
            if (canvas != null) {
                paint.style = Paint.Style.FILL
                paint.color = fill
                canvas.drawRoundRect(RectF(x, top, x + w, top + h), 10f, 10f, paint)
            }
            // Day-of-month, top-left.
            val dom = Calendar.getInstance().apply { timeInMillis = dayStart }
                .get(Calendar.DAY_OF_MONTH).toString()
            paint.textSize = 22f
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.color = when {
                greyed -> p.faint
                onAccent -> p.accentText
                else -> p.muted
            }
            if (canvas != null) canvas.drawText(dom, x + 9f, top + 28f, paint)
            if (greyed) return
            // Block count, centred.
            if (blocks > 0 && canvas != null) {
                paint.textSize = 40f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                paint.color = if (onAccent) p.accentText else p.text
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(blocks.toString(), x + w / 2f, top + 76f, paint)
                paint.textAlign = Paint.Align.LEFT
                paint.typeface = android.graphics.Typeface.DEFAULT
            }
            val dayEnd = dayStart + DAY_MS
            // Ring = a blind (settings-off) window touched this day.
            if (canvas != null && windows.any { !it.gap && it.touchesDay(dayStart, dayEnd, now) }) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = p.danger
                canvas.drawCircle(x + w - 17f, top + 17f, 9f, paint)
                paint.style = Paint.Style.FILL
            }
            // Half dot = a service interruption touched this day.
            if (canvas != null && windows.any { it.gap && it.touchesDay(dayStart, dayEnd, now) }) {
                paint.style = Paint.Style.FILL
                paint.color = p.warning
                canvas.drawArc(RectF(x + w - 26f, top + h - 26f, x + w - 8f, top + h - 8f),
                    90f, 180f, true, paint)
            }
        }

        private fun appLabel(pkg: String): String = try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }

        // ---- text plumbing (wrap + draw-or-measure) ----

        private fun section(canvas: Canvas?, title: String) {
            paint.textSize = 28f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.color = p.accent
            if (canvas != null) canvas.drawText(title, PAD, y + 30f, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT
            y += 48f
        }

        private fun divider(canvas: Canvas?) {
            y += 18f
            if (canvas != null) {
                paint.color = p.stroke
                paint.strokeWidth = 2f
                canvas.drawLine(PAD, y, W - PAD, y, paint)
            }
            y += 26f
        }

        /** Draw (or measure) a wrapped paragraph; returns nothing,
         *  advances [y] past it plus a small gap. */
        private fun text(canvas: Canvas?, str: String, size: Float, color: Int, bold: Boolean = false) {
            paint.textSize = size
            paint.color = color
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
            val lineH = size * 1.32f
            for (line in wrap(str, CW)) {
                if (canvas != null) canvas.drawText(line, PAD, y + size, paint)
                y += lineH
            }
            y += size * 0.28f
        }

        private fun wrap(str: String, maxWidth: Float): List<String> {
            if (str.isBlank()) return listOf("")
            val out = mutableListOf<String>()
            var cur = ""
            for (word in str.split(" ")) {
                val trial = if (cur.isEmpty()) word else "$cur $word"
                if (cur.isEmpty() || paint.measureText(trial) <= maxWidth) {
                    cur = trial
                } else {
                    out += cur
                    cur = word
                }
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        private fun blend(c1: Int, c2: Int, f: Float): Int = Color.rgb(
            (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt(),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt(),
            (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt()
        )

        companion object {
            private const val DAY_MS = 24L * 60 * 60 * 1000
        }
    }
}
