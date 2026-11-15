package com.rem.screenguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v0.19.8: the Protection screen (menu restructure,
 * menu-battery-sus-design-2026-11-14, placement map row 5). Hosts
 * what the level picker alone cannot say — what each level trades —
 * and the "Custom fine-tuning…" entry that used to sit inside
 * Home's level card (button and hint moved verbatim; it still opens
 * the existing CustomActivity, unchanged). Choosing a level stays
 * on Home; this screen explains and fine-tunes.
 */
class ProtectionActivity : Activity() {

    private lateinit var levelTv: TextView

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
        root.addView(SgUi.text(this, "Protection", 26f, p.text, bold = true))

        val levelCard = SgUi.card(this, topMarginDp = 18)
        levelCard.addView(SgUi.cardTitle(this, "Protection level"))
        levelTv = SgUi.text(this, "", 18f, p.text, bold = true)
        levelCard.addView(levelTv)
        levelCard.addView(SgUi.body(this,
            "Levels change check frequency only — the proof rules for a block are " +
                "identical at every level. Gentle checks suspicious apps every 4 s, " +
                "other apps every 20 s, and in suspicion mode every 3 s. Balanced: " +
                "3 s / 15 s, suspicion every 2 s. Strict: 2 s / 10 s, suspicion every " +
                "1 s. Faster costs battery; slower costs a little sampling density. " +
                "Full table: How ScreenGuard works, in the menu.", 13.5f).apply {
            setPadding(0, dp(8), 0, 0)
        })
        levelCard.addView(SgUi.divider(this))
        // Moved verbatim from Home's level card (v0.19.8 placement
        // map row 5): the button and its hint.
        levelCard.addView(SgUi.secondaryButton(this, "Custom fine-tuning…") {
            startActivity(Intent(this@ProtectionActivity, CustomActivity::class.java))
        })
        levelCard.addView(SgUi.hint(this, "Counts, windows, cadence and cooldown."))
        root.addView(levelCard)
        root.addView(SgUi.primaryButton(this, "Back") { finish() })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Same wording as Home's level line (refreshUi contract).
        levelTv.text = "Protection level: " + when (Prefs.protectionLevel(this)) {
            "gentle" -> "Gentle"
            "strict" -> "Strict"
            "custom" -> "Custom (hand-tuned)"
            else -> "Balanced"
        }
    }
}
