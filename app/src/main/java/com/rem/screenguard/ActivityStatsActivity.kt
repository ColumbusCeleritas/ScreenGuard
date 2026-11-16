package com.rem.screenguard

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * v0.19.8: the Activity & battery screen (workstream 2 of
 * menu-battery-sus-design-2026-11-14). Shows the sgstats daily
 * counters — the app's own work, which is what spends the battery —
 * plus the capability state (governor rung / benchmark cap) with
 * its recorded reason while either is in force.
 *
 * Honesty contract (design §2): Android does not let an app read
 * its own per-app battery estimate; that number lives in Settings
 * → Battery → ScreenGuard, and the screen says exactly that
 * instead of inventing a percentage.
 */
class ActivityStatsActivity : Activity() {

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
        root.addView(SgUi.text(this, "Activity & battery", 26f, p.text, bold = true))
        root.addView(SgUi.body(this,
            "What ScreenGuard itself did, per day. This work — captures, model " +
                "checks, time in suspicion mode — is what spends the battery.", 13.5f).apply {
            setPadding(0, dp(4), 0, 0)
        })

        // ---- capability state (governor / benchmark cap) ----
        val capCard = SgUi.card(this, topMarginDp = 18)
        capCard.addView(SgUi.cardTitle(this, "Speed on this phone"))
        val govR = Prefs.govRung(this)
        val capR = Prefs.capRung(this)
        if (govR < 0 && capR < 0) {
            capCard.addView(SgUi.body(this,
                "No slow-down in force — this phone is running the level you chose."))
        } else {
            if (capR >= 0) {
                val name = Prefs.rungName(capR).replaceFirstChar { it.uppercase() }
                val detail = Prefs.capFailDetail(this)
                capCard.addView(SgUi.body(this,
                    "Benchmark cap: $name" +
                        if (detail.isNotBlank()) " — $detail" else ""))
            }
            if (govR >= 0) {
                val name = Prefs.rungName(govR).replaceFirstChar { it.uppercase() }
                val reason = Prefs.govReason(this)
                capCard.addView(SgUi.body(this,
                    "Slowed to $name by the governor" +
                        if (reason.isNotBlank()) " — $reason" else ""))
            }
        }
        root.addView(capCard)

        // ---- daily rows ----
        val days = ActivityStats.recentDays(this)
        val daysCard = SgUi.card(this)
        daysCard.addView(SgUi.cardTitle(this, "Last 8 days"))
        if (days.isEmpty()) {
            daysCard.addView(SgUi.body(this,
                "No recorded days yet — the counters start with the guard's next checks."))
        } else {
            var first = true
            for (row in days) {
                if (!first) daysCard.addView(SgUi.divider(this))
                first = false
                daysCard.addView(SgUi.text(this,
                    ActivityStats.dayLabel(row.dayKey), 15.5f, p.text, bold = true))
                daysCard.addView(SgUi.body(this, buildString {
                    append("Captures: ${row.capOk} ok of ${row.capTry} tried")
                    if (row.capFail > 0) append(" · ${row.capFail} failed")
                    append("\nAnalyses: ${row.analyses} · model time ")
                    append(ActivityStats.fmtDurShort(row.analysisMs))
                    append("\nSuspicion mode: ").append(ActivityStats.fmtDurShort(row.susMs))
                    append("\nBlocks: ${row.blocks}")
                    if (row.warns > 0) append(" · warnings: ${row.warns}")
                }, 13.5f))
            }
        }
        root.addView(daysCard)

        // ---- the honest note ----
        val note = SgUi.card(this)
        note.addView(SgUi.cardTitle(this, "About battery numbers"))
        note.addView(SgUi.body(this,
            "Android's own battery estimate for this app is in Settings → Battery → " +
                "ScreenGuard — Android does not let an app read that number for itself, " +
                "so this screen counts the work instead. Suspicion-mode time is sampled " +
                "every ~15 s while the guard runs, so it can be off by a few seconds " +
                "per start and stop.", 13.5f))
        root.addView(note)

        root.addView(SgUi.primaryButton(this, "Back") { finish() })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
    }
}
