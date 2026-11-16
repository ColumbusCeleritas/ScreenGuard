package com.rem.screenguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v0.19.9 (UI flattening, Alejandro's order): the Appearance
 * screen — a top-level menu row of its own. The Appearance &
 * Supporter Pack card that lived inside the abolished Settings
 * screen (v0.19.0, play-iap) moves here with its wiring and
 * texts verbatim: the current-theme line (with the Supporter
 * Pack owned suffix) and the Themes… entry. Protection is free
 * and identical for everyone; themes are the only paid cosmetic.
 */
class AppearanceActivity : Activity() {

    private lateinit var themeTv: TextView
    private var createdTheme: String = ""

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshUi(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdTheme = Prefs.themeKey(this)
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
        root.addView(SgUi.text(this, "Appearance", 26f, p.text, bold = true))

        // ---- Appearance & Supporter Pack (v0.19.0, play-iap) ----
        val themeCard = SgUi.card(this, topMarginDp = 18)
        themeTv = SgUi.text(this, "", 15f, p.text)
        themeCard.addView(themeTv)
        themeCard.addView(SgUi.body(this,
            "Accent themes for these screens. Protection is free and identical for everyone.", 13.5f).apply {
            setPadding(0, dp(6), 0, 0)
        })
        themeCard.addView(SgUi.secondaryButton(this, "Themes…") {
            startActivity(Intent(this@AppearanceActivity, ThemesActivity::class.java))
        })
        root.addView(themeCard)

        root.addView(SgUi.primaryButton(this, "Back") { finish() })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.themeKey(this) != createdTheme) { recreate(); return }
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun refreshUi() {
        if (::themeTv.isInitialized) {
            val theme = SgTheme.current(this)
            themeTv.text = SgUi.labelValue(
                this, "Theme",
                theme.name + if (SupporterGate.entitled(this)) " · Supporter Pack owned" else ""
            )
        }
    }
}
