package com.rem.screenguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Play-readiness priority 5 (2026-10-31): the prominent disclosure
 * for AccessibilityService use, built to the verified Play policy
 * text (policy audit section 1, support.google.com/.../10964491):
 *  - in the app itself, shown in the normal first-run flow (Main
 *    auto-opens it once per process until accepted, and Setup
 *    step 1 re-opens it whenever consent is missing);
 *  - SEPARATE from every other disclosure: this screen covers only
 *    AccessibilityService screenshot access — no privacy-policy or
 *    terms text is bundled into it;
 *  - describes the data accessed through the AccessibilityService
 *    and exactly how it is used and shared;
 *  - requires an affirmative action ("I understand and agree")
 *    before the accessibility settings screen is ever opened;
 *  - declining records nothing and returns to Home, from where the
 *    disclosure is re-triggered by the card on Home or by Setup
 *    step 1 (the non-consent / re-trigger path the declaration
 *    demo video must show).
 * Consent is versioned (Prefs.DISCLOSURE_VERSION): a material
 * change to this text bumps the version and consent is asked again.
 */
class DisclosureActivity : Activity() {

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
        root.addView(SgUi.text(this, "How ScreenGuard uses Accessibility", 26f, p.text, bold = true))
        root.addView(SgUi.body(this,
            "Please read this before turning on ScreenGuard protection.", 14f).apply {
            setPadding(0, dp(6), 0, 0)
        })

        val card = SgUi.card(this, topMarginDp = 18)
        card.addView(SgUi.cardTitle(this, "What ScreenGuard accesses"))
        card.addView(SgUi.body(this,
            "ScreenGuard's Accessibility Service takes screenshots of " +
                "whatever is on your screen, in any app, again and again " +
                "while protection is on. A screenshot can contain anything " +
                "you can see: messages, photos, web pages and other private " +
                "content. ScreenGuard also sees which app is in front, so " +
                "it knows what it is looking at."))
        card.addView(SgUi.divider(this))
        card.addView(SgUi.cardTitle(this, "How those screenshots are used"))
        card.addView(SgUi.body(this,
            "Each screenshot is checked only on this phone, by models " +
                "stored on this phone, to detect adult content. If adult " +
                "content is detected under ScreenGuard's fixed rules, " +
                "ScreenGuard covers the screen and sends you Home, and the " +
                "app that showed it is paused for a short time. Nothing " +
                "else is done with a screenshot."))
        card.addView(SgUi.divider(this))
        card.addView(SgUi.cardTitle(this, "What is never done"))
        card.addView(SgUi.body(this,
            "Screenshots are checked in memory and are never saved, " +
                "never sent off this phone, and never shared with anyone. " +
                "ScreenGuard has no internet permission, so it cannot send " +
                "them anywhere. The only record kept on this phone is the " +
                "time, the app's name and the detection score — never the " +
                "picture itself."))
        card.addView(SgUi.divider(this))
        card.addView(SgUi.cardTitle(this, "Your choice and control"))
        card.addView(SgUi.body(this,
            "You do not have to agree. If you choose \"Not now\", " +
                "ScreenGuard will not open the accessibility settings and " +
                "protection stays off; you can read this disclosure again " +
                "at any time from the Home screen or from Setup step 1. " +
                "If you agree and turn ScreenGuard on, you can turn its " +
                "Accessibility Service off at any time in Android Settings, " +
                "or uninstall ScreenGuard, and the screenshots stop at once."))
        root.addView(card)

        root.addView(SgUi.primaryButton(this,
            "I understand and agree — continue") {
            Prefs.setDisclosureAccepted(this)
            DebugLog.log(this, "DISCLOSURE accepted v" + Prefs.DISCLOSURE_VERSION)
            if (!isServiceEnabled()) {
                // Consent flow continues straight to the permission
                // grant, as the declaration demo video must show.
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            finish()
        })
        root.addView(SgUi.secondaryButton(this,
            "Not now — I do not agree") {
            DebugLog.log(this, "DISCLOSURE declined v" + Prefs.DISCLOSURE_VERSION)
            finish()
        })
        root.addView(SgUi.hint(this,
            "Agreeing only lets ScreenGuard open the accessibility settings. " +
                "Protection starts only when you turn ScreenGuard on there."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        })
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, GuardService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }
}
