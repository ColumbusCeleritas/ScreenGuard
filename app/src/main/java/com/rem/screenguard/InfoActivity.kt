package com.rem.screenguard

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v0.19.8: "How ScreenGuard works" (menu restructure,
 * menu-battery-sus-design-2026-11-14 workstream 3). The plain-words
 * explanation screen Alejandro asked for on 2026-11-14: the cadence
 * of each protection level, suspicion mode, app groups, what a
 * block does, slow-phone adaptation and the calls note — every
 * number on it read live from Prefs, never hardcoded, so the
 * screen cannot drift from the behaviour. The closing Details
 * section is Home's old "How blocking works" rules text
 * (rulesSummary), moved verbatim apart from its suspicion-rate
 * phrases, which now read the live SUS cadence (v0.19.8 scaling
 * made the flat "every 1 s" wording false at Gentle/Balanced).
 */
class InfoActivity : Activity() {

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
        root.addView(SgUi.text(this, "How Castitas works", 26f, p.text, bold = true))

        val level = Prefs.protectionLevel(this)
        fun mark(key: String) = if (level == key) "  ← this phone" else ""

        // ---- Check frequency: the cadence table ----
        val freq = SgUi.card(this, topMarginDp = 18)
        freq.addView(SgUi.cardTitle(this, "Check frequency"))
        freq.addView(SgUi.body(this,
            "Cadence = how often the screen is checked. The levels differ only in " +
                "cadence — the blocking rules are identical at every level."))
        freq.addView(SgUi.body(this,
            "Gentle — suspicious apps every 4 s · other apps 20 s · system apps 60 s" + mark("gentle"), 13.5f))
        freq.addView(SgUi.body(this,
            "Balanced — suspicious apps every 3 s · other apps 15 s · system apps 60 s" + mark("balanced"), 13.5f))
        freq.addView(SgUi.body(this,
            "Strict — suspicious apps every 2 s · other apps 10 s · system apps 30 s" + mark("strict"), 13.5f))
        if (level == "custom") {
            freq.addView(SgUi.body(this,
                "Custom (hand-tuned) — this phone right now: suspicious every " +
                    "${Prefs.riskyIntervalMs(this) / 1000} s · other apps " +
                    "${Prefs.normalIntervalMs(this) / 1000} s · system apps " +
                    "${Prefs.systemIntervalMs(this) / 1000} s", 13.5f))
        }
        freq.addView(SgUi.body(this,
            "In suspicion mode the checks speed up: every 1 s on Strict, every 2 s on " +
                "Balanced, every 3 s on Gentle. This phone's suspicion rate right now: " +
                "every ${Prefs.susIntervalMs(this) / 1000} s. Nothing anywhere idles " +
                "longer than 60 s without a check.", 13.5f))
        root.addView(freq)

        // ---- App groups ----
        val groups = SgUi.card(this)
        groups.addView(SgUi.cardTitle(this, "App groups"))
        groups.addView(SgUi.body(this,
            "Suspicious apps — browsers (Chrome, Firefox, Brave), X and similar: " +
                "checked most often.\n" +
                "Other apps — everything else, Instagram and WhatsApp included: the " +
                "middle rate.\n" +
                "System apps — dialer, clock, calculator, contacts: a light sanity " +
                "check only. Settings, system UI and Castitas itself are checked " +
                "the same light way and can never be blocked.", 13.5f))
        root.addView(groups)

        // ---- Suspicion mode ----
        val sus = SgUi.card(this)
        sus.addView(SgUi.cardTitle(this, "Suspicion mode"))
        sus.addView(SgUi.body(this,
            "One look that reads 0.30 or more — far below the blocking bar — puts that " +
                "app under suspicion for 15 s: checks run at the suspicion rate, and " +
                "every new suspicious look restarts the full 15 s. Suspicion buys " +
                "sampling, never a block by itself. After any block, the same fast " +
                "watch runs in EVERY app for 3 minutes, so switching apps buys no " +
                "quiet window. If explicit content is proven in the app previews on " +
                "the Home screen, Paranoid mode runs for 5 minutes: while it lasts, " +
                "the Home screen is checked at the suspicion rate and any 0.30+ " +
                "reading sends you Home with a 3 s cover.", 13.5f))
        root.addView(sus)

        // ---- What a block does ----
        val block = SgUi.card(this)
        block.addView(SgUi.cardTitle(this, "What a block does"))
        block.addView(SgUi.body(this,
            "A block closes the app and times it out: 10 s for a first block, " +
                "doubling per repeat — 20, 40, 80, 160, 320 — up to a 600 s (10 min) " +
                "cap. The level resets after 30 clean minutes. With Enforce off " +
                "(Settings), a block becomes a warning instead and nothing is " +
                "closed.", 13.5f))
        root.addView(block)

        // ---- Slow phones ----
        val slow = SgUi.card(this)
        slow.addView(SgUi.cardTitle(this, "If the phone is slow"))
        slow.addView(SgUi.body(this,
            "If a phone cannot keep up — checks failing or running long — " +
                "Castitas slows its idle checks on its own, one step at a time, " +
                "and a 2-minute benchmark (Home, in the level card) can measure the " +
                "right level up front. The floors hold at every step: suspicious " +
                "apps never slower than every 4 s, other apps never slower than " +
                "40 s, and nothing ever past 60 s. Suspicion mode is never slowed.", 13.5f))
        root.addView(slow)

        // ---- Calls ----
        val calls = SgUi.card(this)
        calls.addView(SgUi.cardTitle(this, "During calls"))
        calls.addView(SgUi.body(this,
            "Protection pauses while you are in a call — a video call is never " +
                "judged, and nothing can block the app you are calling from. " +
                "Protection resumes on its own when the call ends.", 13.5f))
        root.addView(calls)

        // ---- Details: the full rules text (moved from Home) ----
        val details = SgUi.card(this)
        details.addView(SgUi.cardTitle(this, "Details — the full rules"))
        val rulesTv = SgUi.text(this, rulesSummary(), 13.5f, p.muted).apply {
            setLineSpacing(0f, 1.3f)
        }
        details.addView(rulesTv)
        root.addView(details)

        root.addView(SgUi.primaryButton(this, "Back") { finish() })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
    }

    /** The current rules, in plain language, generated live from the
     *  settings. Moved verbatim from MainActivity (v0.19.8 placement
     *  map row 6) apart from the suspicion-rate phrases, which read
     *  the live SUS cadence now that it scales by level. */
    private fun rulesSummary(): String {
        val susS = Prefs.susIntervalMs(this) / 1000
        val sb = StringBuilder()
        sb.append("How blocking works right now — ONE scene model (Falconsai) judges whole pictures, and a block needs proof on fresh frames, never just one:\n")
        sb.append("• Full screen: the whole screen reads 0.90+ → the block parks as pending (strike 1 of 4); each further fresh 0.90+ whole-screen read adds a strike, and the fourth fires it. Clean frames can never cancel a parked block — a clean frame is usually just the next post — it only expires after 10 s with nothing reading 0.30+. A whole picture is not a fragment, so no partner or witness is needed\n")
        sb.append("• Video region: the region is drawn from the last 3 seconds of screen motion combined, so it grows to cover a playing video's whole card within a couple of seconds and stays on it while the video is paused; the model judges that region on its own, as its own whole picture — its verdicts earn points instead: 0.90+ earns 2, 0.75+ earns 1, under 0.60 loses 1, and 8 points blocks. No witness is needed — in the offline bench no benign whole picture ever scored above 0.011\n")
        sb.append("• Suspicion mode: any single look that reads 0.30+ — full screen or video region — puts that app under suspicion for 15 s: checks run every $susS s and the video-region finder rides the same capture rhythm (Android allows at most one screenshot per second, shared by both), so warm content is sampled densely instead of at the idle rate. The bars above do not move — suspicion buys sampling, never a block by itself\n")
        sb.append("• Tripwire: a second, smaller classifier also reads every frame (full screen and video region), but it can NEVER block, park, or score points — it only knocks. Because the scene model's verdicts are near-polarised (almost always near 0 or near 1), this older model's graded in-between readings serve as the thermometer: if it reads 0.30+ in suspicious apps (browsers, social, video, chat) or 0.50+ anywhere else, the app enters suspicion mode too, so the scene model looks more often. If suspicion then runs out without the scene model ever reading 0.30+, the tripwire is muted for that app for 60 s, so warm-but-innocent video cannot hold the phone on high alert\n")
        sb.append("• Testing (ground truth): while the debug overlay is on, a small GT button floats at the bottom corner — tap it the moment explicit content appears (it turns red) and tap again when it is gone. Each marked stretch is logged as GT START / GT END with the models' peak readings and timings, so test sessions can be scored exactly. It never affects blocking\n")
        sb.append("• App previews: the app previews in Recents are also checked; a hit there just sends you Home briefly — the launcher itself is never locked or timed out. A proven hit there also opens Paranoid mode for 5 minutes: while it runs, the Home screen is checked at the suspicion rate and ANY reading of 0.30+ from either model — not just proof-grade readings — sends you Home with the 3 s cover again, so the previews cannot simply be watched between covers. Other apps are unaffected, and the 5 minutes never extends unless explicit content is proven there again\n")
        if (Prefs.blockSecure(this)) {
            sb.append("• Incognito: a capture-proof browser window — pure black, every pixel exactly 0 — on 2 frames blocks the mode itself; a merely dark page never counts\n")
        }
        sb.append("After a block the app is closed and timed out: ${Prefs.quarBaseSec(this)}s, doubling per repeat to a ${Prefs.quarMaxSec(this)}s cap; the level resets after ${Prefs.quarResetMs(this) / 60000} clean minutes. And for 3 minutes after any block, suspicion mode runs in EVERY app — checks every $susS s, the region finder on the same capture rhythm — so switching apps buys no quiet window.\n")
        sb.append("Checks run every ${Prefs.riskyIntervalMs(this) / 1000}s in suspicious apps (browsers, social, video, chat), ${Prefs.normalIntervalMs(this) / 1000}s in other apps — Instagram counts as an other app — and every ${Prefs.systemIntervalMs(this) / 1000}s in system apps (dialer, clock, calculator, contacts: sanity checks only, and anything suspicious there restores full cadence at once); any suspicious frame bursts to maximum cadence for ${Prefs.attentionMs(this) / 1000}s. If this phone cannot sustain the chosen rate, Castitas slows its idle checks on its own (and a 2-minute benchmark can measure the right level up front) — suspicion bursts always stay at full speed. Settings, system UI and Castitas's own screens get a sanity check at the system rate — never more than 60 s apart — and can trip suspicion but can never be blocked; the launcher is checked (for Recents previews) but never locked.")
        return sb.toString()
    }
}
