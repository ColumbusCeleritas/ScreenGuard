package com.rem.screenguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The Protection screen.
 * v0.19.8: created by the menu restructure
 * (menu-battery-sus-design-2026-11-14, placement map row 5) holding
 * what the level picker alone cannot say — what each level trades —
 * and the "Custom fine-tuning…" entry that used to sit inside
 * Home's level card.
 * v0.19.9 (UI flattening, Alejandro's order): this screen gains the
 * content — the whole level card moves here from Home (benchmark
 * button + recommendation line, the segmented Gentle / Balanced /
 * Strict selector, the chosen-vs-effective line and the explainer),
 * and the Protection toggles (Enforce, Block incognito / secure
 * windows) move here from the abolished Settings screen. The
 * settings-lock freeze is preserved exactly: this screen's gated
 * set (the three level buttons + the two toggles) freezes under
 * the same `editable = UNLOCKED || OPEN` rule, driven by the same
 * 1.5 s refresh pattern Home and Settings ran. Choosing a level,
 * explaining it and fine-tuning it now all live in one place.
 */
class ProtectionActivity : Activity() {

    private lateinit var levelTv: TextView
    // v0.13.0: the capability benchmark's presence — the
    // recommendation line above the preset selector and the
    // chosen-vs-effective line under it (see refreshUi). Moved
    // from Home with the level card in v0.19.9.
    private lateinit var benchTv: TextView
    private lateinit var effTv: TextView
    private val gatedViews = mutableListOf<View>()
    private var createdTheme: String = ""

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshUi(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SgUi.applyMotion(this)
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
        root.addView(SgUi.screenTitle(this, "Protection"))

        // ---- Protection level: segmented presets (moved from
        // Home's level card in v0.19.9, wiring verbatim) ----
        val levelCard = SgUi.card(this, topMarginDp = 18)
        levelCard.addView(SgUi.cardTitle(this, "Protection level"))
        levelTv = SgUi.text(this, "", 18f, p.text, bold = true)
        levelCard.addView(levelTv)

        // v0.13.0 (capability design §3): the benchmark button sits
        // ABOVE the preset selector, with its recommendation line —
        // measure first, then choose; a benchmark result locks the
        // modes above this phone's cap (see levelBtn). Deliberately
        // NOT in gatedViews: the benchmark only measures, so it
        // stays live under the settings lock (design decision 5).
        levelCard.addView(SgUi.secondaryButton(this, "Benchmark this phone (~2 minutes)") {
            startBenchmark()
        })
        benchTv = SgUi.hint(this, "")
        levelCard.addView(benchTv)

        val currentLevel = Prefs.protectionLevel(this)
        val capRungHome = Prefs.capRung(this)
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = SgUi.rounded(this@ProtectionActivity, p.surface2, 14, p.stroke)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        fun levelBtn(name: String, key: String): Button {
            val selected = currentLevel == key
            // v0.13.0: a mode ABOVE this phone's benchmark cap renders
            // locked (dimmed); tapping it explains why from the
            // stored benchmark record instead of applying it. The
            // selection on record is never rewritten by the cap.
            val rungOfKey = when (key) { "gentle" -> 1; "strict" -> 3; else -> 2 }
            val aboveCap = capRungHome >= 0 && rungOfKey > capRungHome
            val b = Button(this).apply {
                text = name
                isAllCaps = false
                textSize = 14.5f
                minHeight = dp(48)
                background = if (selected) SgUi.rounded(this@ProtectionActivity, p.accent, 10)
                    else SgUi.rounded(this@ProtectionActivity, android.graphics.Color.TRANSPARENT, 10)
                // Run 6: unselected segment text muted -> text; the
                // segments read as available options, not disabled
                // controls (selection still carried by fill + bold).
                setTextColor(if (selected) p.accentText else p.text)
                if (selected) setTypeface(typeface, Typeface.BOLD)
                if (aboveCap) alpha = 0.45f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (aboveCap) {
                        val detail = Prefs.capFailDetail(this@ProtectionActivity)
                        android.widget.Toast.makeText(
                            this@ProtectionActivity,
                            "$name is above this phone's measured cap. " +
                                if (detail.isNotBlank()) detail else "Re-run the benchmark after a phone change.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        DebugLog.log(this@ProtectionActivity, "UI protection level $key tapped but above cap=${Prefs.rungName(capRungHome)}")
                    } else {
                        Prefs.applyPreset(this@ProtectionActivity, key)
                        DebugLog.log(this@ProtectionActivity, "UI protection level set: $key")
                        recreate()
                    }
                }
            }
            seg.addView(b)
            gatedViews.add(b)
            return b
        }
        levelBtn("Gentle", "gentle")
        levelBtn("Balanced", "balanced")
        levelBtn("Strict", "strict")
        levelCard.addView(seg)
        // v0.13.0: chosen vs effective, shown only while they differ
        // (a cap or a governor rung is in force) — text set by refreshUi.
        effTv = SgUi.hint(this, "")
        levelCard.addView(effTv)
        // Run 2 overflow fix: the level explainer is two short lines,
        // never a prose paragraph.
        levelCard.addView(SgUi.body(this,
            "Levels change check frequency and cooldown length only.\n" +
                "Proof rules are identical. Hand-tuning switches to Custom.", 13.5f).apply {
            setPadding(0, dp(10), 0, 0)
        })
        // v0.19.11 (paradigm 9): the long explainer folds behind
        // a disclosure — the two-line summary above stays as the
        // lead; the folded text is byte-identical to v0.19.10's.
        levelCard.addView(SgUi.disclosure(this, "What each level trades") { d ->
            d.addView(SgUi.body(this,
                "Levels change check frequency only — the proof rules for a block are " +
                    "identical at every level. Gentle checks suspicious apps every 4 s, " +
                    "other apps every 20 s, and in suspicion mode every 3 s. Balanced: " +
                    "3 s / 15 s, suspicion every 2 s. Strict: 2 s / 10 s, suspicion every " +
                    "1 s. Faster costs battery; slower costs a little sampling density. " +
                    "Full table: How Castitas works, in the menu.", 13.5f))
        })
        levelCard.addView(SgUi.divider(this))
        // Moved verbatim from Home's level card (v0.19.8 placement
        // map row 5): the button and its hint.
        levelCard.addView(SgUi.secondaryButton(this, "Custom fine-tuning…") {
            startActivity(Intent(this@ProtectionActivity, CustomActivity::class.java))
        })
        levelCard.addView(SgUi.hint(this, "Counts, windows, cadence and cooldown."))
        root.addView(levelCard)

        // ---- Protection toggles (moved from the abolished
        // Settings screen in v0.19.9, wiring verbatim; both stay
        // in the settings-lock gated set) ----
        val togglesCard = SgUi.card(this)
        val enforceSw = SgUi.styledSwitch(this, "Enforce").apply {
            isChecked = Prefs.enforceMode(this@ProtectionActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@ProtectionActivity).edit().putBoolean("enforce", checked).apply()
            }
        }
        togglesCard.addView(enforceSw)
        togglesCard.addView(SgUi.hint(this, "Quarantine the app on detection. Off = warn only."))
        gatedViews.add(enforceSw)
        togglesCard.addView(SgUi.divider(this))
        val secureSw = SgUi.styledSwitch(this, "Block incognito / secure windows").apply {
            isChecked = Prefs.blockSecure(this@ProtectionActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@ProtectionActivity).edit().putBoolean("block_secure", checked).apply()
            }
        }
        togglesCard.addView(secureSw)
        togglesCard.addView(SgUi.hint(this, "Capture-proof screens in browsers."))
        gatedViews.add(secureSw)
        root.addView(togglesCard)

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

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, GuardService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    /** Consent gate for every path that would open the accessibility
     *  settings: without recorded disclosure consent, show the
     *  disclosure instead; with it, go straight to the settings. */
    private fun openAccessibilityOrDisclosure() {
        if (Prefs.disclosureAccepted(this)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            DebugLog.log(this, "DISCLOSURE re-triggered (consent missing)")
            startActivity(Intent(this, DisclosureActivity::class.java))
        }
    }

    /**
     * v0.13.0: arm + open the benchmark. Needs the accessibility
     * service (the benchmark IS the guard's own capture path); with
     * it off, route to Setup step 1 instead (capability design §3).
     * The service's bench machine picks the request up from prefs.
     * (Moved from Home with the level card in v0.19.9; Home's Setup
     * step 4 keeps its own copy.)
     */
    private fun startBenchmark() {
        if (!isServiceEnabled()) {
            DebugLog.log(this, "BENCH requested but accessibility is off — routing to Setup step 1")
            openAccessibilityOrDisclosure()
            return
        }
        if (!Prefs.get(this).getBoolean("bench_active", false)) {
            Prefs.get(this).edit()
                .putBoolean("bench_run", true)
                .putBoolean("bench_cancel", false)
                .putBoolean("capbench", true)
                .apply()
            DebugLog.log(this, "BENCH requested from Protection")
        }
        startActivity(Intent(this, BenchmarkActivity::class.java))
    }

    private fun refreshUi() {
        if (::levelTv.isInitialized) {
            levelTv.text = "Protection level: " + when (Prefs.protectionLevel(this)) {
                "gentle" -> "Gentle"
                "strict" -> "Strict"
                "custom" -> "Custom (hand-tuned)"
                else -> "Balanced"
            }
        }
        // v0.13.0: the benchmark recommendation line + the
        // chosen-vs-effective line (visible only while a cap or a
        // governor rung holds the effective mode below the chosen).
        if (::benchTv.isInitialized) {
            val capR = Prefs.capRung(this)
            benchTv.text = if (capR >= 0) {
                val capName = Prefs.rungName(capR).replaceFirstChar { it.uppercase() }
                val base = "Best mode for this phone: $capName"
                if (Prefs.capEpisodeOk(this)) base
                else "$base · 1 s bursts are not sustainable on this phone"
            } else {
                "Not benchmarked yet — the 2-minute check finds the fastest mode this phone can sustain."
            }
        }
        if (::effTv.isInitialized) {
            val chosenR = Prefs.chosenRung(this)
            val effR = Prefs.effRung(this)
            effTv.text = if (effR < chosenR) {
                val why = when {
                    Prefs.govRung(this) == effR -> "captures were failing on this phone"
                    Prefs.capRung(this) == effR -> "benchmark cap for this phone"
                    else -> "reduced for this phone"
                }
                "Running as: ${Prefs.rungName(effR).replaceFirstChar { it.uppercase() }} ($why)"
            } else ""
        }
        refreshLockUi()
    }

    /** v0.5.0: the settings-lock freeze for the gated views this
     *  screen hosts (the level buttons + the two protection
     *  toggles) — the same rule Home and Settings applied, over
     *  this screen's own gated set; the state machine (Prefs) is
     *  one. */
    private fun refreshLockUi() {
        val state = Prefs.lockState(this)
        val editable = state == Prefs.LOCK_UNLOCKED || state == Prefs.LOCK_OPEN
        gatedViews.forEach { it.isEnabled = editable }
    }
}
