package com.rem.screenguard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v0.19.8: the Accountability screen — the vc86 Home card's content,
 * moved verbatim into its own Activity by the menu restructure
 * (menu-battery-sus-design-2026-11-14, placement map row 9).
 * Deliberately NOT lock-gated, exactly as the card was: reading the
 * record, and sharing it with a partner, weakens nothing.
 */
class AccountabilityActivity : Activity() {

    private lateinit var installedTv: TextView
    private lateinit var stateTv: TextView
    private lateinit var weekTv: TextView
    private lateinit var eventsTv: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshUi(); ui.postDelayed(this, 1500) }
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(20), dp(26), dp(20), dp(28))
        }
        root.addView(SgUi.text(this, "Accountability", 26f, p.text, bold = true))
        val card = SgUi.card(this, topMarginDp = 18)
        card.addView(SgUi.cardTitle(this, "Accountability record"))
        card.addView(SgUi.body(this,
            "An on-device record of installs, accessibility off/on times and blocks. " +
                "It never leaves this phone unless you share the report yourself."))
        installedTv = SgUi.body(this, "")
        card.addView(installedTv)
        stateTv = SgUi.body(this, "")
        card.addView(stateTv)
        weekTv = SgUi.body(this, "")
        card.addView(weekTv)
        eventsTv = SgUi.text(this, "", 12.5f, p.muted, mono = true)
        card.addView(eventsTv)
        card.addView(SgUi.primaryButton(this, "Share accountability report…") {
            AlertDialog.Builder(this@AccountabilityActivity)
                .setTitle("Share accountability report")
                .setItems(arrayOf("Last 7 days", "Last 30 days", "Last 90 days")) { _, which ->
                    AccountabilityReport.shareReport(
                        this@AccountabilityActivity, intArrayOf(7, 30, 90)[which]
                    )
                }
                .show()
        })
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
        Accountability.reconcile(this, false)
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    /** v0.19.10: "1 block" / "2 blocks" — mirrors the report
     *  sheet's pluralisation helper (AccountabilityReport.n). */
    private fun n(count: Int, singular: String): String =
        "$count $singular" + if (count == 1) "" else "s"

    /** The vc86 card's refresh, verbatim logic — one wording for
     *  the screen and the report, as before. */
    private fun refreshUi() {
        if (!::installedTv.isInitialized) return
        val svcOn = Accountability.isServiceEnabled(this)
        val (inst, upd) = Accountability.installInfo(this)
        installedTv.text = buildString {
            append("Installed: ").append(Accountability.fmtDate(inst))
            if (upd > inst) append(" · Updated: ").append(Accountability.fmtDate(upd))
        }
        val onSince = Accountability.onSinceWall(this)
        val offSince = Accountability.offSinceWall(this)
        stateTv.text = when {
            svcOn && onSince > 0 ->
                "Accessibility: ON since " + Accountability.fmtDateTime(onSince)
            svcOn -> "Accessibility: ON"
            offSince > 0 ->
                "Accessibility: OFF since " + Accountability.fmtDateTime(offSince) +
                    " — protection is blind"
            else -> "Accessibility: OFF — protection is blind"
        }
        val nowMs = System.currentTimeMillis()
        val wk = Accountability.stats(this, nowMs - 7L * 24 * 60 * 60 * 1000, nowMs)
        weekTv.text = "This week: ${n(wk.blocks, "block")} · ${n(wk.blindCount, "blind window")} " +
            "(${Accountability.fmtDur(wk.blindMs)}) · ${n(wk.interruptionCount, "interruption")}"
        val recent = Accountability.displayItems(this).takeLast(10).asReversed()
        eventsTv.text =
            if (recent.isEmpty()) "(no events yet)"
            else recent.joinToString("\n") { it.line }
    }
}
