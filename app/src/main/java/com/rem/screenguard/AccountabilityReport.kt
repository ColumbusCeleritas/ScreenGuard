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
 * MediaStore Images (Pictures/Castitas) and shared with
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
                    Environment.DIRECTORY_PICTURES + File.separator + "Castitas"
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
        Toast.makeText(activity, "Saved to Pictures/Castitas/$name", Toast.LENGTH_LONG).show()
        DebugLog.log(activity, "ACCT REPORT ok $name (${days}d)")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_SUBJECT, "Castitas accountability report")
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
        // v0.19.8 (renderer fix 1): Calendar stepping, never fixed
        // DAY_MS arithmetic — a period crossing the fall-back day
        // drifted every later cell an hour into the previous local
        // day (the Nov 11 field report: Thu Nov 5 rendered under
        // Friday, and today got no cell at all).
        val fromDayStart: Long = run {
            val c = Calendar.getInstance()
            c.timeInMillis = toDayStart
            c.add(Calendar.DAY_OF_YEAR, -(days - 1))
            Accountability.dayStartOf(c.timeInMillis)
        }
        val stats = Accountability.stats(ctx, fromDayStart, now)
        val windows = Accountability.windows(ctx)
        val recordStartDay: Long = Accountability.dayStartOf(Accountability.recordStartWall(ctx))

        fun layout(canvas: Canvas?): Float {
            y = 56f
            // ---- header ----
            text(canvas, "Castitas — Accountability report", 44f, p.text, bold = true)
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
            // v0.19.8 (renderer fix 3): the period is the requested
            // window; when the record began inside it, say so right
            // under it — the framed days before the record are not
            // framed as witnessed days.
            if (recordStartDay > fromDayStart) {
                text(
                    canvas,
                    "Record began ${Accountability.fmtDate(recordStartDay)} — days before that were not witnessed.",
                    27f, p.muted
                )
            }
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
                // v0.19.8 (renderer fix 4): singular/plural via n() —
                // the shipped text read "1 times" in the field.
                if (stats.blindCount == 0) "Accessibility turned off: 0 times"
                else "Accessibility turned off: ${n(stats.blindCount, "time")} · blind " +
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
                // v0.19.8 (renderer fix 2): the legend names BOTH
                // numbers a cell carries — the shipped "Cell number
                // = blocks" read as false whenever the visible
                // numbers were the dates.
                "Top-left number = day of the month. Number in the centre = blocks that day. " +
                    "Ring (top right of a cell) = a blind window touched that day · half dot " +
                    "(bottom right) = a service interruption · grey = before the record began " +
                    "— not witnessed, not shown as clean.",
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
                    // v0.19.8 (renderer fix 4): "1 blocks" /
                    // "1 warnings" in the field — pluralise.
                    if (counts[0] > 0) parts += n(counts[0], "block") +
                        if (counts[2] > 0) " (${counts[2]} incognito)" else ""
                    if (counts[1] > 0) parts += n(counts[1], "warning")
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
                // v0.19.8 (renderer fix 5): the list now carries
                // block lines too — "accessibility events" alone
                // would understate what the section holds.
                text(canvas, "No events in this period.", 28f, p.muted)
            } else {
                for (item in items) text(canvas, item.line, 26f, p.text)
            }
            divider(canvas)
            // ---- footer ----
            text(
                canvas,
                "Made on this phone by Castitas. It records what the app could see: accessibility " +
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
            // v0.19.8 (renderer fix 1): every row and cell instant
            // is produced by Calendar stepping from gridStart, so
            // each cell is a true local midnight on its true
            // weekday — the shipped fixed-DAY_MS stepping drifted
            // an hour at the fall-back day, shifting every later
            // cell one weekday and pushing today past the period
            // end (no cell). A 7-day period now renders 7 cells.
            var week = 0
            while (true) {
                val rowCal = Calendar.getInstance()
                rowCal.timeInMillis = gridStart
                rowCal.add(Calendar.DAY_OF_YEAR, week * 7)
                val rowStart = Accountability.dayStartOf(rowCal.timeInMillis)
                if (rowStart > toDayStart) break
                for (col in 0..6) {
                    val cellCal = Calendar.getInstance()
                    cellCal.timeInMillis = rowStart
                    cellCal.add(Calendar.DAY_OF_YEAR, col)
                    val dayStart = Accountability.dayStartOf(cellCal.timeInMillis)
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
                // v0.19.8 (renderer fix 3): a pre-record cell is
                // the page background itself — the shipped 0.5
                // blend read as a witnessed cell at a glance.
                greyed -> p.bg
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
            if (greyed) {
                // v0.19.8 (renderer fix 3): a centred dash where a
                // block count would sit — a pre-record cell cannot
                // be mistaken for a witnessed zero-block day.
                if (canvas != null) {
                    paint.textSize = 40f
                    paint.typeface = android.graphics.Typeface.DEFAULT
                    paint.color = p.faint
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("—", x + w / 2f, top + 76f, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
                return
            }
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
            // v0.19.8 (renderer fix 1): the day ends at the next
            // local midnight, not at +24 h (see calendar()).
            val dayEnd = run {
                val c = Calendar.getInstance()
                c.timeInMillis = dayStart
                c.add(Calendar.DAY_OF_YEAR, 1)
                Accountability.dayStartOf(c.timeInMillis)
            }
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

        /** v0.19.8 (renderer fix 4): "1 block" / "2 blocks" —
         *  the one pluralisation helper for the whole sheet. */
        private fun n(count: Int, singular: String): String =
            "$count $singular" + if (count == 1) "" else "s"

        // v0.19.8: the old DAY_MS constant is gone with the
        // companion that held it — every day computation in this
        // sheet is Calendar-stepped (renderer fix 1); the
        // fixed-length constant was the drift's source.
    }
}
