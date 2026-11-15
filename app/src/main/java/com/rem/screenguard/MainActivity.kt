package com.rem.screenguard

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
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
 * Home — the hub.
 * UI-polish Run 1 (Oct 28, 2026): presentation rebuilt on the SgUi
 * tokens — hero protection card, segmented presets, grouped cards.
 * v0.19.8 MENU RESTRUCTURE (menu-battery-sus-design-2026-11-14,
 * Alejandro's order: "the app is now just too long — divide the
 * views and have a menu"): Home keeps the header, the disclosure
 * card (Play requires it in the Home flow), the protection-status
 * hero, the protection-level card and the Setup checklist — plus a
 * Menu card whose rows open the separate screens everything else
 * moved to (Protection / How ScreenGuard works / Accountability /
 * Activity & battery / Event log / Settings). Every element that
 * left Home exists exactly once in its new screen; the placement
 * map in the design doc is the audit basis. The settings-lock
 * freeze still applies here to the gated views Home hosts (the
 * level buttons and the Setup step-1 button); SettingsActivity
 * freezes its own gated set with the same rule.
 */
class MainActivity : Activity() {

    private lateinit var statusTv: TextView
    // v0.12.1: the notifications row of the Setup checklist (label
    // re-written by refreshUi as the grant lands — see notifAllowed).
    private lateinit var notifBtn: Button
    private lateinit var levelTv: TextView
    // v0.13.0: the capability benchmark's Home presence — the
    // recommendation line above the preset selector and the
    // chosen-vs-effective line under it (see refreshUi).
    private lateinit var benchTv: TextView
    private lateinit var effTv: TextView
    private val gatedViews = mutableListOf<View>()
    // v0.19.0 (play-iap): the theme this instance was built with
    // (onResume recreates if the picker changed it underneath us,
    // so the new palette applies). The Appearance card itself moved
    // to SettingsActivity in v0.19.8.
    private var createdTheme: String = ""
    // v0.19.7 accountability (D1 + design §6): the Setup step-1
    // shortcut, hidden once accessibility is granted and replaced
    // by the ON row + the recording hint.
    private lateinit var accBtn: Button
    private lateinit var accOnTv: TextView
    private lateinit var accOffHintTv: TextView
    // Play prominent disclosure (priority 5): the Home card that
    // re-triggers DisclosureActivity until consent is recorded.
    private lateinit var disclosureCard: LinearLayout
    companion object {
        // Auto-open the disclosure at most once per process, so a
        // decline returns to Home instead of looping back into it;
        // Home's card and Setup step 1 remain the re-trigger paths.
        private var disclosureAutoShown = false
    }
    private val REQ_NOTIF = 1201 // v0.12.1: POST_NOTIFICATIONS request code
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshUi(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdTheme = Prefs.themeKey(this)
        // v0.19.0: Supporter Pack entitlement is re-queried from Play
        // on every app start. Themes only — a billing failure changes
        // nothing else (see BillingRepository's isolation note).
        BillingRepository.refresh(this)
        val p = SgUi.palette(this)

        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        if (!SgUi.isDark(this)) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(SgUi.dp(this@MainActivity, 20), SgUi.dp(this@MainActivity, 26),
                SgUi.dp(this@MainActivity, 20), SgUi.dp(this@MainActivity, 28))
        }
        fun dp(v: Int) = SgUi.dp(this, v)

        // ---- Header: real versionName (was the stale hardcoded v0.11.0) ----
        val versionName = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        root.addView(SgUi.text(this, "ScreenGuard", 30f, p.text, bold = true))
        root.addView(SgUi.body(this,
            "v$versionName  ·  On-device guard · No internet permission", 13.5f).apply {
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(SgUi.hint(this,
            "Screenshots are never saved; only time, app and score are logged."))

        // ---- Play prominent disclosure card (priority 5) ----
        // In the normal Home flow (not a menu/settings page); visible
        // only until consent is recorded (refreshUi toggles it). The
        // full disclosure itself lives in DisclosureActivity, kept
        // separate from every other disclosure as Play requires.
        disclosureCard = SgUi.card(this, topMarginDp = 18)
        disclosureCard.addView(SgUi.cardTitle(this, "Before you turn on protection"))
        disclosureCard.addView(SgUi.body(this,
            "ScreenGuard uses Android's Accessibility Service to take " +
                "screenshots of your screen and check them on this phone " +
                "for adult content. Please read the full disclosure and " +
                "agree before enabling it."))
        disclosureCard.addView(SgUi.primaryButton(this,
            "Read the accessibility disclosure") {
            startActivity(Intent(this@MainActivity, DisclosureActivity::class.java))
        })
        root.addView(disclosureCard)

        // ---- Hero: protection status (statusTv contract unchanged) ----
        val hero = SgUi.card(this, topMarginDp = 18)
        hero.addView(SgUi.cardTitle(this, "Protection status"))
        statusTv = SgUi.text(this, "", 15f, p.text).apply { setLineSpacing(0f, 1.35f) }
        hero.addView(statusTv)
        root.addView(hero)

        // ---- Protection level: segmented presets ----
        val levelCard = SgUi.card(this)
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
            background = SgUi.rounded(this@MainActivity, p.surface2, 14, p.stroke)
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
                background = if (selected) SgUi.rounded(this@MainActivity, p.accent, 10)
                    else SgUi.rounded(this@MainActivity, android.graphics.Color.TRANSPARENT, 10)
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
                        val detail = Prefs.capFailDetail(this@MainActivity)
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "$name is above this phone's measured cap. " +
                                if (detail.isNotBlank()) detail else "Re-run the benchmark after a phone change.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        DebugLog.log(this@MainActivity, "UI protection level $key tapped but above cap=${Prefs.rungName(capRungHome)}")
                    } else {
                        Prefs.applyPreset(this@MainActivity, key)
                        DebugLog.log(this@MainActivity, "UI protection level set: $key")
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
        // v0.19.8: the divider + "Custom fine-tuning…" entry moved
        // to ProtectionActivity (placement map row 5) — the level
        // card keeps choosing; explaining and fine-tuning live in
        // the Protection screen now.
        root.addView(levelCard)

        // ---- Setup checklist ----
        val setupCard = SgUi.card(this)
        setupCard.addView(SgUi.cardTitle(this, "Setup"))
        setupCard.addView(SgUi.body(this, "Three system steps keep ScreenGuard running, plus an optional benchmark."))
        accBtn = SgUi.primaryButton(this,
            "1 ·  Enable ScreenGuard accessibility") {
            openAccessibilityOrDisclosure()
        }
        setupCard.addView(accBtn)
        // v0.19.7 (D1, B1 folded in): the shortcut joins the settings-
        // lock freeze, and once accessibility is granted refreshUi
        // hides it entirely — the one-tap jump to the very settings
        // screen that can silence the guard disappears, replaced by
        // this status row. Turning the guard off stays possible, just
        // deliberate: Android Settings, by hand (see the hint under
        // this card). DisclosureActivity's consent path is untouched.
        gatedViews.add(accBtn)
        accOnTv = SgUi.text(this, "Accessibility: ON", 15.5f, p.success, bold = true)
        setupCard.addView(accOnTv)
        setupCard.addView(SgUi.secondaryButton(this,
            "2 ·  Exclude from battery optimisation") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        setupCard.addView(SgUi.hint(this, "Step 2 keeps the guard alive in the background."))
        // v0.12.1, step 3: the status-bar shield needs Android 13+
        // POST_NOTIFICATIONS. Same in-place pattern as steps 1–2:
        // the row requests the grant; its label flips when granted
        // (refreshUi). Refusing costs nothing — protection works
        // fully, only the shield icon cannot show.
        notifBtn = SgUi.secondaryButton(this, "") { requestNotifPermission() }
        setupCard.addView(notifBtn)
        setupCard.addView(SgUi.hint(this, "Shows the shield icon in the status bar while protection is on. Optional — protection works without it."))
        // v0.13.0, step 4 (capability design §3): the benchmark, as a
        // Setup step after notifications — "a phone gets benchmarked
        // when it is set up". Optional and never forced: without a
        // benchmark there is no cap, and the governor remains the
        // backstop for a phone that struggles.
        setupCard.addView(SgUi.secondaryButton(this,
            "4 ·  Benchmark this phone (~2 minutes)") {
            startBenchmark()
        })
        setupCard.addView(SgUi.hint(this, "Finds the fastest protection level this phone can sustain. Optional — the guard adapts on its own if a phone struggles."))
        root.addView(setupCard)
        // v0.19.7 (D1): the honest hint, shown once granted — the way
        // out is named, and so is the fact that it is recorded.
        // v0.19.8: the tail now points at the Accountability SCREEN
        // (the card moved behind the menu below).
        accOffHintTv = SgUi.hint(this,
            "To turn ScreenGuard off, use Android Settings → Accessibility. Every off and on is recorded — see Accountability in the menu below.")
        root.addView(accOffHintTv)

        // ---- Menu (v0.19.8) ----
        // Everything that used to stack below Setup on Home lives
        // behind these six rows now, one screen each — see the
        // design's placement map for where every element went.
        val menuCard = SgUi.card(this)
        menuCard.addView(SgUi.cardTitle(this, "Menu"))
        menuCard.addView(SgUi.secondaryButton(this, "Protection…") {
            startActivity(Intent(this@MainActivity, ProtectionActivity::class.java))
        })
        menuCard.addView(SgUi.hint(this, "What the levels trade, and custom fine-tuning."))
        menuCard.addView(SgUi.secondaryButton(this, "How ScreenGuard works") {
            startActivity(Intent(this@MainActivity, InfoActivity::class.java))
        })
        menuCard.addView(SgUi.hint(this, "Check frequency, suspicion mode, and what a block does."))
        menuCard.addView(SgUi.secondaryButton(this, "Accountability") {
            startActivity(Intent(this@MainActivity, AccountabilityActivity::class.java))
        })
        menuCard.addView(SgUi.hint(this, "The on-device record: installs, off/on times, blocks."))
        menuCard.addView(SgUi.secondaryButton(this, "Activity & battery") {
            startActivity(Intent(this@MainActivity, ActivityStatsActivity::class.java))
        })
        menuCard.addView(SgUi.hint(this, "Captures, checks and suspicion time per day."))
        menuCard.addView(SgUi.secondaryButton(this, "Event log") {
            startActivity(Intent(this@MainActivity, EventLogActivity::class.java))
        })
        menuCard.addView(SgUi.hint(this, "Warnings and blocks, latest first."))
        menuCard.addView(SgUi.secondaryButton(this, "Settings") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        })
        menuCard.addView(SgUi.hint(this, "Settings lock, protection toggles, diagnostics, appearance, privacy."))
        root.addView(menuCard)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)

        // First-run flow: opening the app presents the disclosure
        // until it is accepted (once per process — see the companion
        // flag — so declining lands back on Home, not in a loop).
        if (!Prefs.disclosureAccepted(this) && !disclosureAutoShown) {
            disclosureAutoShown = true
            startActivity(Intent(this, DisclosureActivity::class.java))
        }
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

    override fun onResume() {
        super.onResume()
        // v0.19.7: reconcile the accountability ledger against the
        // live accessibility state (catches callback-less deaths and
        // settings changes made while Home was away).
        Accountability.reconcile(this, false)
        // v0.19.0: the palette was read at onCreate; if the theme
        // changed while Home sat under the picker, rebuild with it.
        if (Prefs.themeKey(this) != createdTheme) { recreate(); return }
        ui.post(refresh)
    }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, GuardService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    /** v0.12.1: true when the status-bar shield notification may be posted.
     *  Before Android 13 there is no runtime grant to ask for. */
    private fun notifAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * v0.13.0: arm + open the benchmark. Needs the accessibility
     * service (the benchmark IS the guard's own capture path); with
     * it off, route to Setup step 1 instead (capability design §3).
     * The service's bench machine picks the request up from prefs.
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
            DebugLog.log(this, "BENCH requested from Home")
        }
        startActivity(Intent(this, BenchmarkActivity::class.java))
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifAllowed()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            DebugLog.log(this, "UI notifications permission requested (shield icon)")
        } else {
            refreshUi()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) refreshUi()
    }

    private fun refreshUi() {
        if (::disclosureCard.isInitialized) {
            disclosureCard.visibility =
                if (Prefs.disclosureAccepted(this)) View.GONE else View.VISIBLE
        }
        if (::notifBtn.isInitialized) {
            notifBtn.text = if (notifAllowed())
                "3 ·  Notifications allowed — shield icon on"
            else
                "3 ·  Allow notifications (shield icon)"
        }
        // v0.19.7: one read of the live accessibility state drives
        // the hero line (text unchanged) and the D1 shortcut swap.
        val svcOn = isServiceEnabled()
        if (::accBtn.isInitialized) {
            accBtn.visibility = if (svcOn) View.GONE else View.VISIBLE
            accOnTv.visibility = if (svcOn) View.VISIBLE else View.GONE
            accOffHintTv.visibility = if (svcOn) View.VISIBLE else View.GONE
        }
        val p = Prefs.get(this)
        val sb = StringBuilder()
        sb.append("Accessibility service: ").append(if (svcOn) "ON" else "OFF (turn it on in Setup, step 1)").append("\n")
        sb.append("Model loaded: ").append(if (p.getBoolean("model_ok", false)) "yes (Falconsai)" else "not yet / failed").append("\n")
        // v0.11.0: the FAL latch state on Home (the v0.10.3 FP pattern) —
        // a latched-off model must be visible here, not only in a log
        // that can be cleared before export.
        val vc = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) {
            -1
        }
        if (vc >= 0 && Prefs.falLatchedVersion(this) == vc) {
            sb.append("FAL state: LATCHED OFF (")
                .append(Prefs.falFailStage(this)).append(": ").append(Prefs.falFailDetail(this))
                .append(")\n")
        } else {
            sb.append("FAL state: ARMED\n")
        }
        // v0.11.6: the tripwire classifier's state, mirroring the
        // FAL line — written by the service at load/run time. An
        // unavailable tripwire is not an error state for the guard
        // (Falconsai-only is exactly v0.11.5), but it is stated,
        // never silent.
        val clsState = p.getString("cls_state", null)
        sb.append("CLS state: ").append(clsState ?: "— (service not started yet)").append("\n")
        // v0.13.0 (capability design §2): the hero carries the
        // capability state in plain words while it is in force — a
        // governor de-rate, and at FLOOR the honest banner.
        if (Prefs.govRung(this) >= 0) {
            sb.append("Mode reduced for this phone — captures were failing\n")
        }
        if (p.getBoolean("gov_floor_banner", false)) {
            sb.append("This phone cannot sustain ScreenGuard's checks.\n")
        }
        sb.append("Frames analysed: ").append(p.getInt("checks", 0))
            .append("   Blocks: ").append(p.getInt("blocks", 0))
            .append("   Incognito blocks: ").append(p.getInt("secure_blocks", 0)).append("\n")
        sb.append("Loop: ticks ").append(p.getInt("ticks", 0))
            .append(" · captures ok ").append(p.getInt("cap_ok", 0))
            .append(" / fail ").append(p.getInt("cap_fail", 0))
            .append(" · diff-skips ").append(p.getInt("diff_skip", 0))
            .append(" · busy-skips ").append(p.getInt("skip_busy", 0))
            .append(" · pkg-skips ").append(p.getInt("skip_pkg", 0)).append("\n")
        val lastMs = p.getLong("last_check_ms", 0)
        if (lastMs > 0) {
            sb.append("Last scene-model score: ").append("%.2f".format(p.getFloat("last_top_score", 0f)))
                .append("   (inference ").append(p.getLong("last_latency_ms", -1)).append(" ms)\n")
        }
        // Run 3: same text, styled — muted "Label:" prefixes and the
        // service/FAL state token coloured (see SgUi.styleStatus).
        statusTv.text = SgUi.styleStatus(this, sb.toString())
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

    /** v0.5.0: the settings-lock freeze for the gated views Home
     *  hosts (the level buttons + the Setup step-1 shortcut). The
     *  lock card itself — status line, switch, unlock/cancel — moved
     *  to SettingsActivity in v0.19.8, which runs the same rule over
     *  its own gated set; the state machine (Prefs) is one. */
    private fun refreshLockUi() {
        val state = Prefs.lockState(this)
        val editable = state == Prefs.LOCK_UNLOCKED || state == Prefs.LOCK_OPEN
        gatedViews.forEach { it.isEnabled = editable }
    }

}
