package com.rem.screenguard

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * v0.19.0 (play-iap): the theme picker.
 *
 * Six accent themes over the unchanged ink surfaces. Sky is free;
 * Sunset, Forest, Violet, Rose and Ocean belong to the Supporter
 * Pack — a one-time purchase, the only thing ScreenGuard sells.
 * Protection is not on this screen, is never gated by anything here,
 * and reads none of it: selecting a theme writes one display pref
 * (Prefs.ui_theme) and every SgUi screen picks the palette up at its
 * next onCreate.
 *
 * While the pack is unowned the supporter rows show their lock state
 * and the unlock button starts the Play purchase flow (via
 * BillingRepository). Where Play billing is unavailable the rows stay
 * locked and a one-line note says so.
 */
class ThemesActivity : Activity() {

    // Rebuild only when the rendered state actually changed — billing
    // callbacks fire on every query, and an unconditional recreate
    // would loop (rebuild -> refresh -> callback -> rebuild).
    private var lastSignature: String? = null

    private val stateListener: () -> Unit = { rebuildIfChanged() }
    private val messageListener: (String) -> Unit = { msg ->
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BillingRepository.refresh(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        BillingRepository.addStateListener(stateListener)
        BillingRepository.addMessageListener(messageListener)
        BillingRepository.refresh(this)
        rebuildIfChanged()
    }

    override fun onPause() {
        BillingRepository.removeStateListener(stateListener)
        BillingRepository.removeMessageListener(messageListener)
        super.onPause()
    }

    private fun signature(): String =
        "${SupporterGate.entitled(this)}|${BillingRepository.available}|" +
            "${BillingRepository.productChecked}|${BillingRepository.priceText}|" +
            Prefs.themeKey(this)

    private fun rebuildIfChanged() {
        if (lastSignature != null && signature() != lastSignature) buildUi()
    }

    private fun buildUi() {
        lastSignature = signature()
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
        root.addView(SgUi.text(this, "Themes", 26f, p.text, bold = true))
        root.addView(
            SgUi.body(
                this,
                "Accent colours for ScreenGuard's own screens. The block covers never change."
            ).apply { setPadding(0, dp(4), 0, 0) }
        )

        val entitled = SupporterGate.entitled(this)

        // ---- Supporter Pack card ----
        val packCard = SgUi.card(this, topMarginDp = 18)
        packCard.addView(SgUi.cardTitle(this, "Supporter Pack"))
        if (entitled) {
            packCard.addView(
                SgUi.body(
                    this,
                    "Owned — all themes are unlocked. Thank you for supporting ScreenGuard."
                )
            )
        } else {
            packCard.addView(
                SgUi.body(
                    this,
                    "A one-time purchase that unlocks the five supporter themes. " +
                        "Protection is free and identical for everyone, with or without it."
                )
            )
            val price = BillingRepository.priceText
            if (price != null) {
                packCard.addView(SgUi.hint(this, "One-time purchase: $price"))
            }
            packCard.addView(
                SgUi.primaryButton(this, "Unlock all themes — Supporter Pack") {
                    BillingRepository.buy(this)
                }
            )
            if (!BillingRepository.available) {
                packCard.addView(
                    SgUi.hint(this, "Purchases are unavailable right now.")
                        .apply { setPadding(0, dp(8), 0, 0) }
                )
            } else if (BillingRepository.productChecked &&
                BillingRepository.priceText == null
            ) {
                packCard.addView(
                    SgUi.hint(this, "The Supporter Pack isn't on sale yet.")
                        .apply { setPadding(0, dp(8), 0, 0) }
                )
            }
        }
        root.addView(packCard)

        // ---- Theme list ----
        val listCard = SgUi.card(this)
        listCard.addView(SgUi.cardTitle(this, "Accent theme"))
        val currentKey = Prefs.themeKey(this)
        SgTheme.THEMES.forEachIndexed { i, theme ->
            if (i > 0) listCard.addView(SgUi.divider(this))
            val active = theme.key == currentKey
            val locked = theme.supporter && !entitled
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val swatch = View(this).apply {
                background = SgUi.rounded(
                    this@ThemesActivity, SgTheme.swatch(this@ThemesActivity, theme), 9,
                    p.stroke
                )
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    rightMargin = dp(14)
                }
            }
            row.addView(swatch)
            val nameTv = SgUi.text(this, theme.name, 16f, p.text, bold = active).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(nameTv)
            val stateText = when {
                active -> "Active"
                locked -> "Supporter Pack"
                theme.supporter -> "Supporter"
                else -> "Free"
            }
            val stateTv = SgUi.text(
                this, stateText, 13f,
                when {
                    active -> p.accent
                    locked -> p.faint
                    else -> p.muted
                },
                bold = active
            )
            row.addView(stateTv)
            row.setOnClickListener {
                if (locked) {
                    Toast.makeText(
                        this,
                        "${theme.name} is a Supporter Pack theme.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (!active) {
                    Prefs.setThemeKey(this, theme.key)
                    DebugLog.log(this, "UI theme set: ${theme.key}")
                    buildUi()
                }
            }
            listCard.addView(row)
        }
        root.addView(listCard)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
    }
}
