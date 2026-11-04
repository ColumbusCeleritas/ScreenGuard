package com.rem.screenguard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * v0.5.0: the prayer step of the unlock ritual.
 *
 * The prayer must be stayed with for its full length in FOREGROUND time —
 * leaving this screen (onPause) resets it to zero, by design: the point
 * is a pause the urge has to survive, not a button to mash. Completing
 * it only STARTS the unlock timer (Prefs.beginUnlockTimer); settings
 * themselves open after the timer, for the open window.
 *
 * PRODUCTION (v0.15.0, Alejandro's order 2026-10-31): the ritual runs
 * its real durations — prayer 60 s, timer 10 min, open window 10 min
 * (PRAYER_MS here; LOCK_TIMER_MS / LOCK_WINDOW_MS in Prefs). They were
 * 10 s debug values from 2026-10-26 until this release.
 *
 * UI-polish Run 2 (Oct 28, 2026): presentation only — SgUi tokens,
 * a centred card, larger prayer type, and a progress line driven by
 * the SAME ticker values. Ritual timing, the onPause reset and the
 * begin-button action are unchanged from v0.11.14.
 */
class UnlockActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var remainMs = PRAYER_MS
    private var resumeTs = 0L
    private lateinit var countTv: TextView
    private lateinit var beginBtn: Button
    private lateinit var progress: ProgressBar

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (resumeTs != 0L) {
                remainMs -= (now - resumeTs)
                resumeTs = now
            }
            if (remainMs <= 0) {
                if (::progress.isInitialized) progress.progress = 1000
                countTv.text = "The prayer is complete."
                beginBtn.isEnabled = true
                return
            }
            if (::progress.isInitialized) {
                progress.progress = ((PRAYER_MS - remainMs) * 1000 / PRAYER_MS).toInt()
            }
            countTv.text = "Stay with this prayer… ${(remainMs + 999) / 1000}s"
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = SgUi.palette(this)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        if (!SgUi.isDark(this)) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        fun dp(v: Int) = SgUi.dp(this, v)
        fun tv(text: String, size: Float = 16f, bold: Boolean = false, color: Int = p.text) =
            TextView(this).apply {
                this.text = text; textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setLineSpacing(0f, 1.3f)
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            // Run 4: centre the card vertically too — the Run 2/3
            // composition sat high with dead space below the card.
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }
        val card = SgUi.card(this, topMarginDp = 24)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(24), dp(28), dp(24), dp(24))
        card.addView(tv("Unlock ScreenGuard", 24f, true))
        card.addView(tv(
            "You set this lock yourself, for a moment exactly like this one.\n" +
                "The urge will pass. What you are protecting will not.",
            15f, false, p.muted))
        card.addView(SgUi.divider(this))
        card.addView(tv(
            "God, grant me the serenity\nto accept the things I cannot change,\n" +
                "the courage to change the things I can,\n" +
                "and the wisdom to know the difference.",
            20f, false, p.text))
        progress = SgUi.progressLine(this)
        card.addView(progress)
        countTv = tv("Stay with this prayer… ${PRAYER_MS / 1000}s", 17f, true, p.accent)
        card.addView(countTv)
        beginBtn = SgUi.primaryButton(this, "Begin the ${durLabel(Prefs.LOCK_TIMER_MS)} wait") {
            Prefs.beginUnlockTimer(this@UnlockActivity)
            DebugLog.log(this@UnlockActivity, "UNLOCK prayer completed — unlock timer started (${Prefs.LOCK_TIMER_MS / 1000}s)")
            finish()
        }
        beginBtn.isEnabled = false
        card.addView(beginBtn)
        card.addView(tv(
            "Leaving this screen restarts the prayer. After the wait, settings " +
                "open for a ${durLabel(Prefs.LOCK_WINDOW_MS)} window — and a block while you wait cancels the request.",
            12.5f, false, p.muted)) // Run 6: footnote faint -> muted (contrast floor, as SgUi.hint)
        root.addView(card)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumeTs = System.currentTimeMillis()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        resumeTs = 0L
        // By design: leaving resets the minute.
        if (remainMs > 0) remainMs = PRAYER_MS
    }

    companion object {
        // PRODUCTION (v0.15.0): the full 60 s prayer.
        const val PRAYER_MS = 60_000L

        /** "10-second" / "10-minute" style label for a duration. */
        fun durLabel(ms: Long): String =
            if (ms >= 60_000L) "${ms / 60_000}-minute" else "${ms / 1000}-second"
    }
}
