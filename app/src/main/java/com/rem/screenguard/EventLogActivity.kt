package com.rem.screenguard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v0.19.8: the Event log screen — the Home card's content moved
 * verbatim into its own Activity by the menu restructure
 * (menu-battery-sus-design-2026-11-14, placement map row 15). The
 * card sat at the bottom of Home since v0.19.3; the content, the
 * "latest first" contract and the source (Prefs.log — warnings and
 * blocks only) are unchanged.
 */
class EventLogActivity : Activity() {

    private lateinit var logTv: TextView
    private lateinit var emptyRings: RingsView

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshUi(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SgUi.applyMotion(this)
        val p = SgUi.palette(this)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        if (!SgUi.isDark(this)) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        fun dp(v: Int) = SgUi.dp(this, v)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(20), dp(26), dp(20), dp(28))
        }
        root.addView(SgUi.screenTitle(this, "Event log"))
        val card = SgUi.card(this, topMarginDp = 18)
        card.addView(SgUi.cardTitle(this, "Events"))
        card.addView(SgUi.body(this,
            "Latest first · warnings and blocks only — full detail is in the debug log.", 12.5f).apply {
            setPadding(0, 0, 0, dp(6))
        })
        // v0.19.11 (paradigm 10): the motif in the empty state.
        emptyRings = RingsView(this).apply {
            ringColor = p.accent
            centerFill = p.accent
            centerMark = p.accentText
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            }
            visibility = View.GONE
        }
        card.addView(emptyRings)
        logTv = SgUi.text(this, "", 12.5f, p.muted, mono = true)
        card.addView(logTv)
        root.addView(card)
        root.addView(SgUi.primaryButton(this, "Back") { finish() })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun refreshUi() {
        if (!::logTv.isInitialized) return
        val log = Prefs.log(this)
        if (::emptyRings.isInitialized) {
            emptyRings.visibility = if (log.isBlank()) View.VISIBLE else View.GONE
        }
        logTv.text = if (log.isBlank()) "(no events yet)" else log.split("\n").reversed().joinToString("\n")
    }
}
