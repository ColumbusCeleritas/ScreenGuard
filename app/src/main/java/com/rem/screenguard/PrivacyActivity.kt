package com.rem.screenguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * v0.19.9 (UI flattening, Alejandro's order): the Privacy
 * screen — a top-level menu row of its own. The Privacy &
 * open source card that lived inside the abolished Settings
 * screen (Play G3 remainder) moves here with its wiring and
 * texts verbatim: the in-app privacy policy and the GPL-3.0
 * licence / third-party notices, both read through
 * TextInfoActivity exactly as before.
 */
class PrivacyActivity : Activity() {

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
        root.addView(SgUi.text(this, "Privacy & open source", 26f, p.text, bold = true))

        // ---- Privacy & open source (Play G3 remainder) ----
        val infoCard = SgUi.card(this, topMarginDp = 18)
        infoCard.addView(SgUi.body(this,
            "Castitas collects nothing and sends nothing anywhere. " +
                "The full privacy policy is readable inside the app, and " +
                "Castitas is free software under GPL-3.0 — its licence " +
                "texts and third-party notices are included too."))
        infoCard.addView(SgUi.secondaryButton(this, "Privacy policy") {
            startActivity(Intent(this@PrivacyActivity, TextInfoActivity::class.java)
                .putExtra(TextInfoActivity.EXTRA_KIND, TextInfoActivity.KIND_PRIVACY))
        })
        infoCard.addView(SgUi.secondaryButton(this, "Open-source licences") {
            startActivity(Intent(this@PrivacyActivity, TextInfoActivity::class.java)
                .putExtra(TextInfoActivity.EXTRA_KIND, TextInfoActivity.KIND_LICENCES))
        })
        root.addView(infoCard)

        root.addView(SgUi.primaryButton(this, "Back") { finish() })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
    }
}
