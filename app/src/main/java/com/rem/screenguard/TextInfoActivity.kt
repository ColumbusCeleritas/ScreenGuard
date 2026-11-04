package com.rem.screenguard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Play-readiness G3 remainder (2026-11-02): the in-app privacy policy
 * and open-source licences screens.
 *
 * G3 requires the privacy policy as link/text inside the app as well
 * as at its public URL. This screen shows the FULL policy text from
 * the bundled asset (byte-matched to the published policy at
 * https://columbusceleritas.com/privacy-policy/ when bundled) and
 * offers the web address as a secondary action. Opening the web
 * address is a VIEW intent handed to the user's browser — the app
 * itself still has no INTERNET permission and fetches nothing.
 *
 * The licences screen shows the third-party notices plus the full
 * GPL-3.0, Apache-2.0 and ONNX Runtime MIT texts, bundled unchanged
 * under assets/licenses/ (byte-identical to the repo's licenses/
 * copies). That makes the published policy's statement true: "The
 * full licence texts and third-party notices for the on-device
 * models are included inside the app."
 *
 * Deliberately separate from DisclosureActivity: Play requires the
 * accessibility disclosure to stand alone, so no policy or licence
 * text is bundled into that screen, and none is bundled here either.
 */
class TextInfoActivity : Activity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_PRIVACY = "privacy"
        const val KIND_LICENCES = "licences"
        const val POLICY_URL = "https://columbusceleritas.com/privacy-policy/"
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

        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_PRIVACY
        val isPrivacy = kind != KIND_LICENCES

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(20), dp(26), dp(20), dp(28))
        }
        root.addView(SgUi.text(this,
            if (isPrivacy) "Privacy policy" else "Open-source licences",
            26f, p.text, bold = true))
        root.addView(SgUi.body(this,
            if (isPrivacy)
                "How ScreenGuard handles what it can see on your screen. " +
                    "This is the full text of the published policy."
            else
                "ScreenGuard is free software under the GNU General Public " +
                    "License v3.0. The notices and full licence texts follow.",
            14f).apply { setPadding(0, dp(6), 0, 0) })

        val card = SgUi.card(this, topMarginDp = 18)
        val bodyText = if (isPrivacy) {
            readAsset("privacy-policy.txt")
        } else {
            buildString {
                append(readAsset("third-party-notices.txt").replace("**", ""))
                append("\n\n========== GNU GENERAL PUBLIC LICENSE v3.0 ==========\n\n")
                append(readAsset("licenses/gpl-3.0.txt"))
                append("\n\n========== APACHE LICENSE 2.0 ==========\n\n")
                append(readAsset("licenses/apache-2.0.txt"))
                append("\n\n========== ONNX RUNTIME — MIT LICENSE ==========\n\n")
                append(readAsset("licenses/onnxruntime-mit.txt"))
            }
        }
        card.addView(SgUi.body(this, bodyText, 13f).apply {
            setLineSpacing(0f, 1.3f)
        })
        root.addView(card)

        if (isPrivacy) {
            root.addView(SgUi.secondaryButton(this, "Open policy on the web") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(POLICY_URL)))
                } catch (_: Exception) {
                    Toast.makeText(this,
                        "No browser available — the policy is at $POLICY_URL",
                        Toast.LENGTH_LONG).show()
                }
            })
            root.addView(SgUi.hint(this,
                "The web copy opens in your browser. ScreenGuard itself " +
                    "has no internet permission and fetches nothing."))
        }
        root.addView(SgUi.secondaryButton(this, "Back") { finish() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        })
        DebugLog.log(this,
            if (isPrivacy) "UI privacy policy opened" else "UI licences screen opened")
    }

    private fun readAsset(path: String): String =
        try {
            assets.open(path).bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            "(This text could not be loaded: ${e.message})"
        }
}
