package com.rem.screenguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v0.19.11 (paradigms 2 + 7): the drawn data visuals — cadence
 * bars, the suspicion timeline, the block ladder, the activity
 * bar chart and the accountability donut. All Canvas/programmatic
 * (the ScoreGraph precedent), all colours from the active
 * palette, all data from the existing ledgers. Nothing here
 * collects or computes protection state.
 */

/** A single horizontal bar: track + proportional fill. */
class BarView(ctx: Context) : View(ctx) {
    var fraction: Float = 0f
    var fillColor: Int = 0
    var trackColor: Int = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val h = height.toFloat()
        val r = h / 2f
        paint.color = trackColor
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, paint)
        val fw = width * fraction.coerceIn(0f, 1f)
        if (fw > 0f) {
            paint.color = fillColor
            canvas.drawRoundRect(0f, 0f, maxOf(fw, 3f * density), h, r, r, paint)
        }
    }
}

/** One cadence bar row: group label · bar (∝ seconds) · value. */
fun cadenceRow(
    ctx: Context, label: String, seconds: Int, maxSeconds: Int,
    highlighted: Boolean
): LinearLayout {
    val p = SgUi.palette(ctx)
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SgUi.dp(ctx, 5) }
    }
    row.addView(SgUi.text(ctx, label, SgUi.SP_SMALL,
        if (highlighted) p.text else p.muted, bold = highlighted).apply {
        layoutParams = LinearLayout.LayoutParams(SgUi.dp(ctx, 86),
            LinearLayout.LayoutParams.WRAP_CONTENT)
    })
    row.addView(BarView(ctx).apply {
        fraction = seconds.toFloat() / maxSeconds.toFloat()
        fillColor = if (highlighted) p.accent else p.faint
        trackColor = p.surface2
        layoutParams = LinearLayout.LayoutParams(0, SgUi.dp(ctx, 10), 1f)
    })
    row.addView(SgUi.text(ctx, "$seconds s", SgUi.SP_SMALL,
        if (highlighted) p.accent else p.muted, bold = highlighted).apply {
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(SgUi.dp(ctx, 42),
            LinearLayout.LayoutParams.WRAP_CONTENT)
    })
    return row
}

/**
 * The suspicion visual (paradigm 2): the 15 s window as a full
 * bar with a warning trigger diamond, then the two long watches
 * on one shared 300 s scale beneath it.
 */
fun susTimeline(ctx: Context): LinearLayout {
    val p = SgUi.palette(ctx)
    val wrap = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SgUi.dp(ctx, 8) }
    }
    fun caption(left: String, right: String): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SgUi.dp(ctx, 3) }
        }
        r.addView(SgUi.text(ctx, left, SgUi.SP_CAPTION, p.muted).apply {
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        r.addView(SgUi.text(ctx, right, SgUi.SP_CAPTION, p.muted))
        return r
    }
    fun bar(fraction: Float, color: Int): BarView = BarView(ctx).apply {
        this.fraction = fraction
        fillColor = color
        trackColor = p.surface2
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, SgUi.dp(ctx, 12)
        ).apply { topMargin = SgUi.dp(ctx, 6) }
    }
    // The window itself: full width = its own 15 s.
    wrap.addView(SgUi.text(ctx, "Suspicion window", SgUi.SP_SMALL, p.text, bold = true))
    wrap.addView(bar(1f, p.accent))
    wrap.addView(caption("◆ a look at 0.30+ opens it", "every new look restarts the 15 s"))
    // The long watches, on one shared 300 s scale.
    wrap.addView(SgUi.text(ctx, "After any block — every app", SgUi.SP_SMALL, p.text,
        bold = true).apply { setPadding(0, SgUi.dp(ctx, 10), 0, 0) })
    wrap.addView(bar(180f / 300f, p.accent2))
    wrap.addView(caption("fast watch, all apps", "3 min"))
    wrap.addView(SgUi.text(ctx, "Paranoid mode — the Home screen", SgUi.SP_SMALL, p.text,
        bold = true).apply { setPadding(0, SgUi.dp(ctx, 10), 0, 0) })
    wrap.addView(bar(1f, p.accent2))
    wrap.addView(caption("after a proven preview hit", "5 min"))
    return wrap
}

/** The block ladder: seven doubling cooldown steps (paradigm 2). */
fun ladderView(ctx: Context): LinearLayout {
    val p = SgUi.palette(ctx)
    val steps = intArrayOf(10, 20, 40, 80, 160, 320, 600)
    val wrap = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SgUi.dp(ctx, 8) }
    }
    wrap.addView(SgUi.text(ctx, "Cooldown ladder — doubles with each repeat",
        SgUi.SP_CAPTION, p.muted))
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SgUi.dp(ctx, 6) }
    }
    for ((i, s) in steps.withIndex()) {
        val last = i == steps.size - 1
        val cell = TextView(ctx).apply {
            text = if (s >= 60) "${s / 60} min" else "$s s"
            textSize = SgUi.SP_CAPTION
            setTextColor(if (last) p.warning else p.text)
            if (last) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = SgUi.rounded(ctx, p.surface2, 999,
                if (last) p.warning else p.stroke)
            setPadding(0, SgUi.dp(ctx, 6), 0, SgUi.dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = SgUi.dp(ctx, 4)
            }
        }
        row.addView(cell)
    }
    wrap.addView(row)
    return wrap
}

/**
 * Analyses per day (paradigm 7): bars in the theme accent,
 * today's bar in accent2, a danger dot over any day that had a
 * block, weekday letters under the baseline.
 */
class BarChartView(ctx: Context) : View(ctx) {

    /** Chronological (oldest → newest) day rows. */
    var rows: List<ActivityStats.DayRow> = emptyList()
    var accent: Int = 0
    var accent2: Int = 0
    var danger: Int = 0
    var muted: Int = 0
    var stroke: Int = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(w: Int, h: Int) {
        val desired = (118 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            if (MeasureSpec.getMode(w) == MeasureSpec.EXACTLY)
                MeasureSpec.getSize(w) else suggestedMinimumWidth,
            if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY)
                MeasureSpec.getSize(h) else desired
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) return
        val density = resources.displayMetrics.density
        val labelH = 16f * density
        val dotHead = 10f * density
        val plotH = height - labelH - dotHead
        val baseY = dotHead + plotH
        val maxV = maxOf(1, rows.maxOf { it.analyses })
        val slot = width.toFloat() / rows.size
        val barW = slot * 0.52f
        // Baseline.
        paint.style = Paint.Style.FILL
        paint.color = stroke
        canvas.drawRect(0f, baseY, width.toFloat(), baseY + 1f * density, paint)
        textPaint.color = muted
        textPaint.textSize = 10.5f * resources.displayMetrics.scaledDensity
        val letters = "SMTWTFS"
        for ((i, row) in rows.withIndex()) {
            val cx = slot * i + slot / 2f
            val frac = row.analyses.toFloat() / maxV
            val barH = maxOf(if (row.analyses > 0) 3f * density else 0f, plotH * frac)
            paint.color = if (i == rows.size - 1) accent2 else accent
            val left = cx - barW / 2f
            val r = 3f * density
            canvas.drawRoundRect(left, baseY - barH, left + barW, baseY, r, r, paint)
            // Square off the bar's bottom corners against the baseline.
            canvas.drawRect(left, baseY - r, left + barW, baseY, paint)
            if (row.blocks > 0) {
                paint.color = danger
                canvas.drawCircle(cx, dotHead / 2f + 1f * density, 3f * density, paint)
            }
            val dow = try {
                val cal = java.util.Calendar.getInstance()
                cal.set(row.dayKey.substring(0, 4).toInt(),
                    row.dayKey.substring(4, 6).toInt() - 1,
                    row.dayKey.substring(6, 8).toInt())
                cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
            } catch (e: Exception) { -1 }
            if (dow in 0..6) {
                canvas.drawText(letters[dow].toString(), cx,
                    baseY + 12.5f * density, textPaint)
            }
        }
    }
}

/**
 * Guarded vs blind (paradigm 7): a ring — success green for the
 * guarded share of the witnessed window, danger red for blind
 * time, the guarded % at the centre. A null fraction (no record
 * yet) draws a full faint ring and a dash: never a fake number.
 */
class DonutView(ctx: Context) : View(ctx) {

    var guardedFraction: Float? = null
    var success: Int = 0
    var danger: Int = 0
    var faint: Int = 0
    var textColor: Int = 0
    var muted: Int = 0

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val sw = 9f * density
        arcPaint.strokeWidth = sw
        val inset = sw / 2f + 2f * density
        val rect = android.graphics.RectF(inset, inset, width - inset, height - inset)
        val frac = guardedFraction
        if (frac == null) {
            arcPaint.color = faint
            canvas.drawArc(rect, 0f, 360f, false, arcPaint)
        } else {
            val f = frac.coerceIn(0f, 1f)
            arcPaint.color = danger
            canvas.drawArc(rect, 0f, 360f, false, arcPaint)
            arcPaint.color = success
            canvas.drawArc(rect, -90f, 360f * f, false, arcPaint)
        }
        val cx = width / 2f
        val cy = height / 2f
        pctPaint.color = textColor
        pctPaint.textSize = 19f * resources.displayMetrics.scaledDensity
        val pct = if (frac == null) "—" else "${(frac.coerceIn(0f, 1f) * 100).toInt()}%"
        canvas.drawText(pct, cx, cy + 2f * density, pctPaint)
        capPaint.color = muted
        capPaint.textSize = 9.5f * resources.displayMetrics.scaledDensity
        canvas.drawText("guarded", cx, cy + 13f * density, capPaint)
    }
}
