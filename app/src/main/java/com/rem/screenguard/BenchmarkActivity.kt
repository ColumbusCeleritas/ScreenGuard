package com.rem.screenguard

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v0.13.0: the benchmark stage (device-capability design §3).
 *
 * Home's "Benchmark this phone" button arms the run (bench_run +
 * capbench prefs) and opens this screen; GuardService's benchmark
 * machine drives the phases against THIS screen through the normal
 * capture path — the existing capbench exception makes our own
 * package analysable, and the scout stands down for the duration.
 *
 * The screen itself is deliberately boring: a neutral field with a
 * deterministic moving pattern (positions are a pure function of
 * elapsed time, so every phone renders the same workload), a live
 * status line mirrored from the service's bench_status pref, and a
 * cancel button. Cancelling — or leaving — writes NOTHING: a
 * cancelled benchmark has no opinion (the service aborts on
 * bench_cancel, and a run whose stage vanished starves its current
 * phase and stops without a cap only if it was mid-flight... it
 * cannot: leaving sets bench_cancel in onDestroy).
 *
 * v0.13.1: when the run ends the service writes an explicit,
 * timestamped RESULT record (bench_result_ts / _cap / _detail)
 * alongside the cap. This screen polls for a result newer than the
 * moment it opened and then swaps to a result view — the cap and
 * the one-line reason, pattern hidden, Close button. (v0.13.0 keyed
 * completion off the status string alone and the screen sat on
 * "Phase ends in 0s" after BENCH done — Alejandro: "it gets
 * stuck?") The poll chain is exception-proofed: one bad read must
 * never stop the polling that would show the outcome.
 *
 * v0.13.3: a live — or merely REQUESTED — run owns this screen
 * outright. v0.13.2 latched `finished` on the STALE "Done" status
 * string from the previous night's run in the gap between this
 * screen opening and the service picking the request up, and the
 * latch then froze that stored result on screen (pattern still
 * drawn) while the new run ran underneath it (field 2026-10-30
 * 09:34, his first screenshot). A stored result may only show
 * when no run is active AND none is waiting to start.
 *
 * Presentation follows the SgUi tokens like the rest of the app.
 */
class BenchmarkActivity : Activity() {

    private lateinit var statusTv: TextView
    private lateinit var phaseTv: TextView
    private lateinit var actionBtn: Button
    private lateinit var patternView: View
    private var finished = false
    private var openedAtMs = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            try {
                refreshStatus()
            } catch (t: Throwable) {
                // Never let one bad prefs read kill the chain — the
                // outcome this poll exists to show may be one tick away.
                try {
                    DebugLog.log(this@BenchmarkActivity, "BENCH screen poll read failed ${t.javaClass.simpleName}: ${t.message}")
                } catch (_: Throwable) {
                    // Logger self-guard — the chain matters more.
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    /** Deterministic neutral pattern: soft bars drifting on a dark
     *  field, a pure function of elapsed milliseconds. */
    private class PatternView(ctx: android.content.Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val startMs = System.currentTimeMillis()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(Color.rgb(16, 22, 34))
            val t = (System.currentTimeMillis() - startMs) / 1000.0
            // A grid of muted blocks; every 7th cell carries a bar
            // whose offset cycles deterministically with t.
            val cols = 6
            val rows = 10
            val cw = w / cols
            val ch = h / rows
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    val shade = 28 + ((i * 37) % 40)
                    paint.color = Color.rgb(shade, shade + 6, shade + 16)
                    canvas.drawRect(c * cw + 3f, r * ch + 3f, (c + 1) * cw - 3f, (r + 1) * ch - 3f, paint)
                    if (i % 7 == 0) {
                        val off = ((t * 40.0 + i * 13.0) % (cw - 12.0)).toFloat()
                        paint.color = Color.rgb(94, 148, 196)
                        canvas.drawRect(c * cw + 6f + off, r * ch + ch * 0.4f, c * cw + 18f + off, r * ch + ch * 0.6f, paint)
                    }
                }
            }
            postInvalidateDelayed(50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAtMs = System.currentTimeMillis()
        val p = SgUi.palette(this)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fun dp(v: Int) = SgUi.dp(this, v)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(20), dp(26), dp(20), dp(28))
        }
        root.addView(SgUi.text(this, "Benchmarking this phone", 24f, p.text, bold = true))
        root.addView(
            SgUi.body(
                this,
                "Keep this screen open. ScreenGuard is measuring how fast this phone can " +
                    "capture and judge screens at each protection level (~2 minutes).",
                13.5f
            ).apply { setPadding(0, dp(6), 0, 0) }
        )
        phaseTv = SgUi.text(this, "Starting…", 17f, p.text, bold = true).apply {
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(phaseTv)
        statusTv = SgUi.body(this, "", 13.5f).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(statusTv)
        val pattern = PatternView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(16) }
        }
        patternView = pattern
        root.addView(pattern)
        actionBtn = SgUi.dangerButton(this, "Cancel benchmark") {
            if (finished) {
                finish()
            } else {
                Prefs.get(this).edit().putBoolean("bench_cancel", true).apply()
                DebugLog.log(this, "BENCH cancel requested from the benchmark screen")
                finish()
            }
        }
        root.addView(actionBtn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(poll) // dedupe: one chain, however we got here
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
    }

    override fun onDestroy() {
        // Leaving mid-run IS a cancel: the service must not keep
        // measuring a stage that is no longer on screen, and a
        // cancelled benchmark writes nothing.
        if (!finished && Prefs.get(this).getBoolean("bench_active", false)) {
            Prefs.get(this).edit().putBoolean("bench_cancel", true).apply()
        }
        Prefs.get(this).edit().putBoolean("capbench", false).apply()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val p = Prefs.get(this)
        val active = p.getBoolean("bench_active", false)
        // v0.13.3: a live — or merely REQUESTED — run owns this
        // screen outright (see the class comment): show the RUNNING
        // state, unlatch any stale outcome, restore the stage. Only
        // when no run is active AND none is waiting to start may a
        // stored result appear.
        val runRequested = p.getBoolean("bench_run", false)
        if (active || runRequested) {
            finished = false
            patternView.visibility = View.VISIBLE
            actionBtn.text = "Cancel benchmark"
            val status = p.getString("bench_status", "") ?: ""
            if (active && status.isNotBlank()) {
                val remainS = ((p.getLong("bench_phase_end", 0L) - System.currentTimeMillis())
                    .coerceAtLeast(0) / 1000)
                phaseTv.text = status
                statusTv.text = "Phase ends in ${remainS}s · results are written only if the run completes."
            } else {
                phaseTv.text = "Starting…"
                statusTv.text = "The benchmark starts in a moment — keep this screen open."
            }
            return
        }
        if (finished) return
        // v0.13.1: the explicit result record is the primary signal —
        // a result written after this screen opened IS the outcome,
        // whether the climb finished all four phases or failed early.
        val resultTs = p.getLong("bench_result_ts", 0L)
        if (resultTs > openedAtMs) {
            showResult(
                cap = p.getInt("bench_result_cap", -1),
                detail = p.getString("bench_result_detail", "") ?: ""
            )
            return
        }
        val status = p.getString("bench_status", "") ?: ""
        if (status.startsWith("Benchmark cancelled")) {
            finished = true
            phaseTv.text = "Benchmark cancelled"
            statusTv.text = status
            actionBtn.text = "Close"
        } else if (status.startsWith("Done")) {
            // Fallback (a result record should always accompany this):
            // still never sit on a dead phase screen.
            finished = true
            phaseTv.text = "Benchmark complete"
            statusTv.text = status
            patternView.visibility = View.GONE
            actionBtn.text = "Close"
        }
    }

    /** v0.13.1: the outcome view — cap, one-line reason, Close. */
    private fun showResult(cap: Int, detail: String) {
        finished = true
        phaseTv.text = "Benchmark complete"
        val capName = Prefs.rungName(cap).replaceFirstChar { it.uppercase() }
        statusTv.text = if (detail.isNotBlank()) {
            "Best mode for this phone: $capName\n$detail"
        } else {
            "Best mode for this phone: $capName"
        }
        patternView.visibility = View.GONE // the stage is done moving
        actionBtn.text = "Close"
    }
}
