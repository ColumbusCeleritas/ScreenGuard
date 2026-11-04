package com.rem.screenguard

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The score graph — a read-only diagnostic instrument (Alejandro's
 * order). It plots every score the guard produced over the retained
 * history ([ScoreHistory]). The point is to SEE whether a playing
 * video produces a real, sustained signal or just noise, before any
 * further rule redesign.
 *
 * v0.11.0: exactly TWO series — FAL FULL (Falconsai on the whole
 * frame) and FAL CROP (Falconsai on the motion-region crop) — one
 * model, two whole-picture passes, nothing else left to plot. (The
 * 21 detector/classifier series of v0.10.6–v0.10.10 left with their
 * models.) Each series has its own colour and every line is thin —
 * series are identified by colour alone, with a swatch legend under
 * the toggles. Missing data (no crop pass on a frame) draws as a
 * GAP, never as a zero.
 *
 * v0.11.7 (Alejandro's order — full debug visibility): the graph
 * gains a second group, CLS FULL and CLS CROP — the tripwire
 * classifier's two readings, plotted beside the Falconsai pair so
 * its graded mid-range signal can be watched against the judge's
 * polarised one. Visibility only: the classifier still feeds
 * nothing but the SUS trigger.
 *
 * v0.12.2: a fifth series, FAL ZOOM (amber #FFB300) — the ZoD
 * burst's best zoom verdict per frame, gaps where no burst ran.
 * Read-only like the rest of this screen.
 *
 * v0.11.14 (Alejandro's order — failure visibility): every failure
 * the guard logged through its FAIL channel is drawn as a thin
 * semi-transparent RED VERTICAL TICK spanning the plot height at
 * its timestamp. Overlapping ticks darken into bands, so a failure
 * storm is visible as a solid red stretch — density IS the signal.
 * The legend gains a red FAIL chip carrying the visible window's
 * failure count. Failures live in the same history store with the
 * same retention as the scores, so they persist across the window
 * controls (1m / 5m / All) exactly like score lines do.
 *
 * Not behind the settings lock: it weakens nothing and is most
 * useful while locked. Read-only — it can never affect detection.
 */
class ScoreGraphActivity : Activity() {

    // ---- series model: 0 = FAL full, 1 = FAL crop, 2 = CLS full, 3 = CLS crop, 4 = FAL zoom ----
    private val seriesShort = arrayOf("FAL full", "FAL crop", "CLS full", "CLS crop", "FAL zoom")

    // UI-polish Run 2: four far-apart hues aligned to the SgUi dark
    // chart surface (sky / emerald / amber / pink) — still mutually
    // distinguishable and none near the FAIL red. The legend, readout
    // spans and chart all read this one array, as before.
    // v0.12.2 (ZoD build spec §5.5): a fifth series, FAL ZOOM, in
    // amber #FFB300, with its own legend chip and toggle.
    private val seriesColors = intArrayOf(
        Color.rgb(56, 189, 248),
        Color.rgb(52, 211, 153),
        Color.rgb(251, 191, 36),
        Color.rgb(244, 114, 182),
        Color.rgb(255, 179, 0)
    )

    // v0.11.14: the failure colour — a flat red, deliberately unlike
    // any series hue at full opacity; ticks draw it semi-transparent
    // (see ChartView.failPaint) so overlaps build into bands.
    private val FAIL_COLOR = Color.rgb(229, 28, 28)

    private fun seriesValue(series: Int, s: ScoreHistory.Sample): Float? = when (series) {
        0 -> s.falFull
        1 -> s.falCrop
        2 -> s.clsFull
        3 -> s.clsCrop
        else -> s.falZoom
    }

    private fun seriesGroupEnabled(series: Int): Boolean = when (series) {
        0 -> showFalFull
        1 -> showFalCrop
        2 -> showClsFull
        3 -> showClsCrop
        else -> showFalZoom
    }

    // ---- view state ----
    private var samples: List<ScoreHistory.Sample> = emptyList()
    private var failures: List<ScoreHistory.Failure> = emptyList() // v0.11.14: FAIL events, same store/retention as the scores
    private var windowMs: Long = 300_000L // default: last 5 minutes; -1 = All
    private var viewEndMs: Long = System.currentTimeMillis()
    private var following: Boolean = true
    private var cursor: ScoreHistory.Sample? = null
    private var showFalFull = true
    private var showFalCrop = true
    private var showClsFull = true
    private var showClsCrop = true
    private var showFalZoom = true // v0.12.2: the FAL ZOOM series toggle
    private var showLines = true

    private lateinit var titleTv: TextView
    private lateinit var chart: ChartView
    private lateinit var readoutTv: TextView
    private lateinit var failLegendTv: TextView // v0.11.14: "FAIL · n" beside the red swatch
    private val legendEntries = arrayOfNulls<View>(5)
    private val windowBtns = mutableListOf<Button>()
    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val refresher = object : Runnable {
        override fun run() {
            samples = ScoreHistory.snapshot()
            failures = ScoreHistory.failureSnapshot()
            if (cursor == null) updateReadout()
            updateTitle()
            updateFailLegend()
            chart.invalidate()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        samples = ScoreHistory.snapshot()
        failures = ScoreHistory.failureSnapshot()

        val gp = SgUi.palette(this)
        window.statusBarColor = gp.bg
        window.navigationBarColor = gp.bg
        if (!SgUi.isDark(this)) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(gp.bg)
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        titleTv = TextView(this).apply {
            textSize = 20f; setTextColor(gp.text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(titleTv)
        root.addView(TextView(this).apply {
            text = "Gaps = not examined (never zeroes). Red ticks = failures.\n" +
                "Drag to pan · tap the chart to inspect one frame."
            textSize = 12f; setTextColor(gp.muted)
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(4), 0, dp(8))
        })

        // Window-size controls.
        val winRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun winBtn(label: String, ms: Long) {
            val b = Button(this).apply {
                text = label; textSize = 13f; isAllCaps = false
                minHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(3); rightMargin = dp(3) }
                setOnClickListener {
                    windowMs = ms
                    following = true
                    cursor = null
                    viewEndMs = System.currentTimeMillis()
                    markWindowBtns()
                    samples = ScoreHistory.snapshot()
                    failures = ScoreHistory.failureSnapshot()
                    updateReadout(); updateTitle(); updateFailLegend(); chart.invalidate()
                }
            }
            windowBtns.add(b); winRow.addView(b)
        }
        winBtn("1m", 60_000L); winBtn("5m", 300_000L); winBtn("All", -1L)
        root.addView(winRow)

        // Series toggles.
        val togRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tog(label: String, apply: (Boolean) -> Unit) {
            togRow.addView(ToggleButton(this).apply {
                textOn = label; textOff = label; text = label
                textSize = 11.5f; isAllCaps = false
                // Run 4: 48dp — the M3 / Google accessibility minimum
                // touch target (the Run 2/3 chips were 40dp).
                minHeight = dp(48)
                isChecked = true
                // Run 2: chip treatment — checked = surface2 + full text,
                // unchecked = surface + faint text (StateListDrawable).
                val onBg = SgUi.rounded(this@ScoreGraphActivity, gp.surface2, 10, gp.accent)
                val offBg = SgUi.rounded(this@ScoreGraphActivity, gp.surface, 10, gp.stroke)
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), onBg)
                    addState(intArrayOf(), offBg)
                }
                // Run 6: unchecked chip text faint -> muted (below
                // the contrast floor at 11.5sp in both themes).
                setTextColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(gp.text, gp.muted)))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(2); rightMargin = dp(2); topMargin = dp(8) }
                setOnCheckedChangeListener { _, checked -> apply(checked); syncLegend(); chart.invalidate() }
            })
        }
        tog("FAL full") { showFalFull = it }
        tog("FAL crop") { showFalCrop = it }
        tog("CLS full") { showClsFull = it }
        tog("CLS crop") { showClsCrop = it }
        tog("FAL zoom") { showFalZoom = it }
        tog("Lines") { showLines = it }
        root.addView(togRow)

        // Colour-swatch legend — one row, all five series (FAL pair,
        // then CLS pair, then FAL zoom).
        // Run 4: swatches get the app's rounded-chip treatment (3dp
        // corners, was hard squares) and tighter label padding — the
        // Run 3 legend row ran right up to the screen edge.
        val legendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        for (i in 0 until 5) {
            val entry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val sw = View(this).apply {
                background = SgUi.rounded(this@ScoreGraphActivity, seriesColors[i], 3)
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { topMargin = dp(2) }
            }
            entry.addView(sw)
            entry.addView(TextView(this).apply {
                text = seriesShort[i]; textSize = 11f; setTextColor(gp.muted)
                setPadding(dp(3), 0, dp(7), 0)
            })
            legendEntries[i] = entry
            legendRow.addView(entry)
        }
        // v0.11.14: the FAIL chip — red swatch + the visible window's
        // failure count, updated by updateFailLegend().
        val failEntry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        failEntry.addView(View(this).apply {
            background = SgUi.rounded(this@ScoreGraphActivity, FAIL_COLOR, 3)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { topMargin = dp(2) }
        })
        failLegendTv = TextView(this).apply {
            text = "FAIL · 0"; textSize = 11f; setTextColor(gp.danger)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(3), 0, 0, 0)
        }
        failEntry.addView(failLegendTv)
        legendRow.addView(failEntry)
        root.addView(legendRow)

        chart = ChartView()
        // Run 2: the chart sits on a rounded surface card.
        chart.background = SgUi.rounded(this, gp.surface, 16, gp.stroke)
        root.addView(chart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8) })

        readoutTv = TextView(this).apply {
            textSize = 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(gp.muted)
            background = SgUi.rounded(this@ScoreGraphActivity, gp.surface, 12, gp.stroke)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        root.addView(readoutTv)

        setContentView(root)
        markWindowBtns()
        updateTitle()
        updateReadout()
        updateFailLegend()
    }

    /** v0.11.14: the FAIL legend chip shows how many failure events
     *  fall inside the currently visible window. */
    private fun updateFailLegend() {
        if (!::failLegendTv.isInitialized) return
        val (st, en) = windowRange()
        val n = failures.count { it.tsMs in st..en }
        failLegendTv.text = "FAIL · $n"
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresher) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Run 2: selected state is now the segmented-control treatment
     *  (filled accent = selected, surface = unselected) PLUS the bold
     *  typeface the original mechanism used — same signal, clearer. */
    private fun markWindowBtns() {
        val gp = SgUi.palette(this)
        val sel = when (windowMs) { 60_000L -> 0; -1L -> 2; else -> 1 }
        windowBtns.forEachIndexed { i, b ->
            val selected = i == sel
            b.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            b.background = SgUi.rounded(this, if (selected) gp.accent else gp.surface2, 10,
                if (selected) null else gp.stroke)
            // Run 6: unselected window text muted -> text (same
            // legibility pass as the Home segments; selection is
            // still carried by the accent fill + bold).
            b.setTextColor(if (selected) gp.accentText else gp.text)
        }
    }

    private fun updateTitle() {
        val win = when (windowMs) { 60_000L -> "last 1 min"; -1L -> "all retained"; else -> "last 5 min" }
        titleTv.text = "Score graph — $win" + if (following) " · LIVE" else " · paused (window buttons rejoin live)"
    }

    private fun syncLegend() {
        for (i in 0 until 5) legendEntries[i]?.alpha = if (seriesGroupEnabled(i)) 1f else 0.3f
    }

    /** Visible time range [start, end] for the current state.
     *  v0.11.14: the range spans the UNION of score and failure
     *  timestamps, so failures stay reachable (and countable) even
     *  in a stretch where no frame was ever analysed — precisely
     *  the drought windows this exists to show. */
    private fun windowRange(): Pair<Long, Long> {
        val first = listOfNotNull(samples.firstOrNull()?.tsMs, failures.firstOrNull()?.tsMs).minOrNull()
        val last = listOfNotNull(samples.lastOrNull()?.tsMs, failures.lastOrNull()?.tsMs).maxOrNull()
        if (first == null || last == null) {
            val now = System.currentTimeMillis()
            return (now - 300_000L) to now
        }
        if (windowMs < 0) {
            val span = maxOf(last - first, 60_000L)
            val pad = span / 20
            return (first - pad) to (last + pad)
        }
        val end = if (following) maxOf(System.currentTimeMillis(), last) else viewEndMs
        return (end - windowMs) to end
    }

    private fun fmt(v: Float?): String = v?.let { "%.2f".format(it) } ?: "–"

    private fun SpannableStringBuilder.colored(text: String, color: Int) {
        val st = length
        append(text)
        setSpan(ForegroundColorSpan(color), st, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun updateReadout() {
        val s = cursor ?: samples.lastOrNull()
        if (s == null) {
            // Run 3: the chart already paints this empty state — the
            // readout card repeating it verbatim was duplication.
            readoutTv.visibility = android.view.View.GONE
            return
        }
        readoutTv.visibility = android.view.View.VISIBLE
        val sb = SpannableStringBuilder()
        sb.append(timeFmt.format(Date(s.tsMs))).append(" · ").append(s.pkg)
        if (cursor == null) sb.append(" · latest (tap chart to pin a frame)")
        sb.append("\n")
        sb.colored("FAL full ${fmt(s.falFull)}", seriesColors[0])
        sb.append("\n")
        if (s.falCrop != null) {
            sb.colored("FAL crop ${fmt(s.falCrop)}", seriesColors[1])
        } else {
            sb.append("FAL crop — no region on this frame")
        }
        // v0.11.7: the CLS pair mirrors the FAL pair (full debug
        // visibility). CLS full shows "–" when the tripwire produced
        // no reading on this frame (unavailable or run failed).
        sb.append("\n")
        sb.colored("CLS full ${fmt(s.clsFull)}", seriesColors[2])
        sb.append("\n")
        if (s.clsCrop != null) {
            sb.colored("CLS crop ${fmt(s.clsCrop)}", seriesColors[3])
        } else {
            sb.append("CLS crop — no region on this frame")
        }
        // v0.12.2: the FAL ZOOM readout rides along only on frames
        // where a ZoD burst actually ran.
        if (s.falZoom != null) {
            sb.append("\n")
            sb.colored("FAL zoom ${fmt(s.falZoom)}", seriesColors[4])
        }
        readoutTv.text = sb
    }

    // ------------------------------------------------------------------

    inner class ChartView : View(this@ScoreGraphActivity) {

        // Run 2: chart paints tokenised to the SgUi palette (was a
        // hardcoded white-chart assumption).
        private val chartPal = SgUi.palette(this@ScoreGraphActivity)
        private val gridPaint = Paint().apply { color = chartPal.stroke; strokeWidth = 1f }
        // Run 5 fix (presentation only): STROKE style — with the
        // default FILL style, drawRect(plot) filled the whole plot
        // area with the faint colour, washing the dark chart slate
        // and covering the gridlines (seen in the first live-data
        // graph shot, shots-5-live2/live2-graph.png). The rect is
        // the plot border; it was a stroke on the pre-Run-2 chart
        // only by accident of the light palette hiding the fill.
        private val axisPaint = Paint().apply {
            color = chartPal.faint; strokeWidth = 1f; style = Paint.Style.STROKE
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = chartPal.muted; textSize = dp(9).toFloat() }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() * 1.5f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chartPal.faint; strokeWidth = 1f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        private val threshLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chartPal.muted; textSize = dp(8).toFloat()
        }
        private val cursorPaint = Paint().apply { color = chartPal.text; strokeWidth = dp(1).toFloat() }
        // v0.11.14: failure ticks — thin, semi-transparent red; a
        // lone failure is a hairline, a storm overlaps into a band.
        private val failPaint = Paint().apply {
            color = Color.argb(110, 229, 28, 28); strokeWidth = dp(1).toFloat()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chartPal.muted; textSize = dp(13).toFloat(); textAlign = Paint.Align.CENTER
        }

        private var plotL = 0f; private var plotT = 0f; private var plotR = 0f; private var plotB = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            plotL = dp(30).toFloat(); plotT = dp(6).toFloat()
            plotR = w - dp(6); plotB = h - dp(18).toFloat()
            if (plotR <= plotL || plotB <= plotT) return
            if (samples.isEmpty() && failures.isEmpty()) {
                canvas.drawText("No scores yet —", w / 2f, h / 2f - dp(10), emptyPaint)
                canvas.drawText("the graph fills as the guard analyses frames.", w / 2f, h / 2f + dp(10), emptyPaint)
                return
            }
            val (start, end) = windowRange()
            val span = maxOf(end - start, 1L)
            fun x(ts: Long): Float = plotL + (ts - start).toFloat() / span * (plotR - plotL)
            fun y(v: Float): Float = plotB - v * (plotB - plotT)

            // Horizontal gridlines + Y labels (fixed 0.0–1.0).
            for (g in 0..5) {
                val v = g * 0.2f
                val yy = y(v)
                canvas.drawLine(plotL, yy, plotR, yy, gridPaint)
                val lbl = "%.1f".format(v)
                canvas.drawText(lbl, plotL - dp(4) - labelPaint.measureText(lbl), yy + dp(3), labelPaint)
            }
            // X time labels: 5 ticks.
            for (i in 0..4) {
                val ts = start + span * i / 4
                val lbl = timeFmt.format(Date(ts))
                val xx = x(ts)
                val tx = when (i) {
                    0 -> xx
                    4 -> xx - labelPaint.measureText(lbl)
                    else -> xx - labelPaint.measureText(lbl) / 2f
                }
                canvas.drawText(lbl, tx, plotB + dp(14), labelPaint)
            }
            // Threshold reference lines (toggleable): the two Falconsai
            // bars — signal grade (confirms a pending block) and the
            // strong bar (parks / counts toward persistence; the crop
            // accumulator's +2 band starts there too).
            if (showLines) {
                for ((v, name) in listOf(0.60f to "0.60 confirm", 0.90f to "0.90 strong")) {
                    val yy = y(v)
                    val path = Path().apply { moveTo(plotL, yy); lineTo(plotR, yy) }
                    canvas.drawPath(path, dashPaint)
                    canvas.drawText(name, plotR - threshLabelPaint.measureText(name) - dp(2), yy - dp(2), threshLabelPaint)
                }
            }
            // Plot border.
            canvas.drawRect(plotL, plotT, plotR, plotB, axisPaint)

            // Series — missing values break the path (gaps, never zeros).
            canvas.save()
            canvas.clipRect(plotL, plotT, plotR, plotB)
            val path = Path()
            for (series in 0 until 5) {
                if (!seriesGroupEnabled(series)) continue
                linePaint.color = seriesColors[series]
                path.reset()
                var pen = false
                for (s in samples) {
                    val v = seriesValue(series, s)
                    if (v == null) { pen = false; continue }
                    val xx = x(s.tsMs); val yy = y(v)
                    if (!pen) { path.moveTo(xx, yy); pen = true } else path.lineTo(xx, yy)
                }
                canvas.drawPath(path, linePaint)
            }
            // v0.11.14: failure ticks — one red vertical line per
            // FAIL event inside the window, drawn over the series
            // (failures are the loudest thing on this chart by
            // design) and under the scrub cursor.
            for (f in failures) {
                if (f.tsMs < start || f.tsMs > end) continue
                val xx = x(f.tsMs)
                canvas.drawLine(xx, plotT, xx, plotB, failPaint)
            }
            // Scrub cursor: vertical line + per-series dots at the pinned sample.
            val cur = cursor
            if (cur != null && cur.tsMs in start..end) {
                val xx = x(cur.tsMs)
                canvas.drawLine(xx, plotT, xx, plotB, cursorPaint)
                for (series in 0 until 5) {
                    if (!seriesGroupEnabled(series)) continue
                    val v = seriesValue(series, cur) ?: continue
                    dotPaint.color = seriesColors[series]
                    canvas.drawCircle(xx, y(v), dp(3).toFloat(), dotPaint)
                }
            }
            canvas.restore()
        }

        // ---- pan (drag) + scrub (tap/drag sets the cursor) ----
        private var downX = 0f
        private var downEndMs = 0L
        private var downPxPerMs = 1.0
        private var panning = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (samples.isEmpty() && failures.isEmpty()) return true
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downEndMs = windowRange().second
                    val (st, en) = windowRange()
                    downPxPerMs = ((plotR - plotL) / maxOf(en - st, 1L)).toDouble()
                    panning = false
                    setCursorFromX(e.x)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    if (abs(dx) > dp(10)) panning = true
                    if (panning && windowMs >= 0) {
                        following = false
                        viewEndMs = clampEnd(downEndMs - (dx / downPxPerMs).toLong())
                        updateTitle()
                    }
                    setCursorFromX(e.x)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> performClick()
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun clampEnd(end: Long): Long {
            // v0.11.14: pan bounds span scores + failures, as windowRange does.
            val first = listOfNotNull(samples.firstOrNull()?.tsMs, failures.firstOrNull()?.tsMs).minOrNull() ?: return end
            val last = listOfNotNull(samples.lastOrNull()?.tsMs, failures.lastOrNull()?.tsMs).maxOrNull() ?: return end
            val lo = first + windowMs / 4
            val hi = maxOf(System.currentTimeMillis(), last) + 2_000L
            return end.coerceIn(lo, hi)
        }

        private fun setCursorFromX(px: Float) {
            val (start, end) = windowRange()
            val span = maxOf(end - start, 1L)
            val frac = ((px - plotL) / (plotR - plotL)).coerceIn(0f, 1f)
            val target = start + (frac * span).toLong()
            var best: ScoreHistory.Sample? = null
            var bestD = Long.MAX_VALUE
            for (s in samples) {
                val d = abs(s.tsMs - target)
                if (d < bestD) { bestD = d; best = s }
            }
            if (best != cursor) {
                cursor = best
                updateReadout()
            }
        }
    }
}
