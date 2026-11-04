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
import android.widget.Switch
import android.widget.TextView

/**
 * Home + settings hub.
 * UI-polish Run 1 (Oct 28, 2026): presentation rebuilt on the SgUi
 * tokens — hero protection card, segmented presets, grouped cards.
 * Behaviour is unchanged from v0.11.14: same prefs keys, same intents,
 * same refreshUi / refreshLockUi text contracts (emoji glyphs removed),
 * same gated-views sweep, same lock-listener guard. The dead slider
 * helpers (moved to CustomActivity in v0.11.0) are dropped.
 */
class MainActivity : Activity() {

    private lateinit var statusTv: TextView
    private lateinit var logTv: TextView
    // v0.12.1: the notifications row of the Setup checklist (label
    // re-written by refreshUi as the grant lands — see notifAllowed).
    private lateinit var notifBtn: Button
    private lateinit var levelTv: TextView
    private lateinit var rulesTv: TextView
    // v0.13.0: the capability benchmark's Home presence — the
    // recommendation line above the preset selector and the
    // chosen-vs-effective line under it (see refreshUi).
    private lateinit var benchTv: TextView
    private lateinit var effTv: TextView
    // v0.5.0: settings lock UI + the controls it freezes
    private lateinit var lockStatusTv: TextView
    private lateinit var lockSw: Switch
    private lateinit var unlockBtn: Button
    private lateinit var cancelUnlockBtn: Button
    private val gatedViews = mutableListOf<View>()
    private val REQ_NOTIF = 1201 // v0.12.1: POST_NOTIFICATIONS request code
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshUi(); ui.postDelayed(this, 1500) }
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(SgUi.dp(this@MainActivity, 20), SgUi.dp(this@MainActivity, 26),
                SgUi.dp(this@MainActivity, 20), SgUi.dp(this@MainActivity, 28))
        }
        fun dp(v: Int) = SgUi.dp(this, v)
        fun weightedRow(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun rowBtn(btn: Button, leftMarginDp: Int = 0) {
            btn.textSize = 14.5f
            btn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                topMargin = dp(10); leftMargin = dp(leftMarginDp)
            }
        }

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

        // ---- Hero: protection status (statusTv contract unchanged) ----
        val hero = SgUi.card(this, topMarginDp = 18)
        hero.addView(SgUi.cardTitle(this, "Protection status"))
        statusTv = SgUi.text(this, "", 15f, p.text).apply { setLineSpacing(0f, 1.35f) }
        hero.addView(statusTv)
        root.addView(hero)

        // ---- Protection level: segmented presets + rules ----
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
        levelCard.addView(SgUi.divider(this))
        val customBtn = SgUi.secondaryButton(this, "Custom fine-tuning…") {
            startActivity(Intent(this@MainActivity, CustomActivity::class.java))
        }
        levelCard.addView(customBtn)
        levelCard.addView(SgUi.hint(this, "Counts, windows, cadence and cooldown."))
        root.addView(levelCard)

        // Run 2 overflow fix: the full rules summary (rulesTv, text
        // contract preserved) moves OUT of the level card into its own
        // card, collapsed behind a toggle — no prose wall on Home.
        val rulesCard = SgUi.card(this)
        rulesCard.addView(SgUi.cardTitle(this, "How blocking works"))
        rulesTv = SgUi.text(this, "", 13.5f, p.muted).apply {
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
        }
        val rulesToggle = SgUi.secondaryButton(this, "Show details") { }
        rulesToggle.setOnClickListener {
            val show = rulesTv.visibility != View.VISIBLE
            rulesTv.visibility = if (show) View.VISIBLE else View.GONE
            rulesToggle.text = if (show) "Hide details" else "Show details"
        }
        rulesCard.addView(rulesToggle)
        rulesCard.addView(rulesTv)
        root.addView(rulesCard)

        // ---- Setup checklist ----
        val setupCard = SgUi.card(this)
        setupCard.addView(SgUi.cardTitle(this, "Setup"))
        setupCard.addView(SgUi.body(this, "Three system steps keep ScreenGuard running, plus an optional benchmark."))
        // Run 4: the Run 1–3 label wrapped to two lines inside the
        // primary button (taller than every other button on Home).
        // Shortened — same step, same destination, one line.
        setupCard.addView(SgUi.primaryButton(this,
            "1 ·  Enable ScreenGuard accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
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

        // ---- Settings lock (v0.5.0 semantics preserved verbatim) ----
        val lockCard = SgUi.card(this)
        lockCard.addView(SgUi.cardTitle(this, "Settings lock"))
        lockStatusTv = SgUi.text(this, "", 15.5f, p.text, bold = true)
        lockCard.addView(lockStatusTv)
        lockSw = SgUi.styledSwitch(this, "Lock settings").apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    Prefs.lockNow(this@MainActivity)
                    DebugLog.log(this@MainActivity, "UI settings LOCKED")
                } else if (Prefs.lockState(this@MainActivity) == Prefs.LOCK_OPEN) {
                    Prefs.unlockFully(this@MainActivity)
                    DebugLog.log(this@MainActivity, "UI settings unlocked (through the open window)")
                }
                refreshLockUi()
            }
        }
        lockCard.addView(lockSw)
        lockCard.addView(SgUi.hint(this, "Changes then need the unlock ritual: prayer + wait."))
        val lockRow = weightedRow()
        unlockBtn = SgUi.secondaryButton(this, "Request unlock…") {
            startActivity(Intent(this@MainActivity, UnlockActivity::class.java))
        }
        rowBtn(unlockBtn)
        lockRow.addView(unlockBtn)
        cancelUnlockBtn = SgUi.secondaryButton(this, "Cancel request") {
            Prefs.cancelUnlock(this@MainActivity)
            DebugLog.log(this@MainActivity, "UNLOCK request cancelled by user")
            refreshLockUi()
        }
        rowBtn(cancelUnlockBtn, leftMarginDp = 10)
        lockRow.addView(cancelUnlockBtn)
        lockCard.addView(lockRow)
        root.addView(lockCard)

        // ---- Protection toggles ----
        val togglesCard = SgUi.card(this)
        togglesCard.addView(SgUi.cardTitle(this, "Protection"))
        val enforceSw = SgUi.styledSwitch(this, "Enforce").apply {
            isChecked = Prefs.enforceMode(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@MainActivity).edit().putBoolean("enforce", checked).apply()
            }
        }
        togglesCard.addView(enforceSw)
        togglesCard.addView(SgUi.hint(this, "Quarantine the app on detection. Off = warn only."))
        gatedViews.add(enforceSw)
        togglesCard.addView(SgUi.divider(this))
        val secureSw = SgUi.styledSwitch(this, "Block incognito / secure windows").apply {
            isChecked = Prefs.blockSecure(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@MainActivity).edit().putBoolean("block_secure", checked).apply()
            }
        }
        togglesCard.addView(secureSw)
        togglesCard.addView(SgUi.hint(this, "Capture-proof screens in browsers."))
        gatedViews.add(secureSw)
        root.addView(togglesCard)

        // ---- Diagnostics (deliberately NOT lock-gated, as before) ----
        val diagCard = SgUi.card(this)
        diagCard.addView(SgUi.cardTitle(this, "Diagnostics"))
        val verboseSw = SgUi.styledSwitch(this, "Verbose debug log").apply {
            isChecked = Prefs.verbose(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@MainActivity).edit().putBoolean("verbose", checked).apply()
                DebugLog.log(this@MainActivity, "UI verbose set to $checked")
            }
        }
        diagCard.addView(verboseSw)
        diagCard.addView(SgUi.hint(this, "Every capture, diff and score."))
        // v0.5.2: live debug readout. Deliberately NOT frozen by the settings
        // lock — it weakens nothing and is most useful while locked.
        val debugSw = SgUi.styledSwitch(this, "Debug overlay").apply {
            isChecked = Prefs.debugOverlay(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@MainActivity).edit().putBoolean("debug_overlay", checked).apply()
                DebugLog.log(this@MainActivity, "UI debug overlay set to $checked")
            }
        }
        diagCard.addView(debugSw)
        diagCard.addView(SgUi.hint(this, "Live model scores on screen, small, top-left."))
        // v0.16.0: score dots master switch — the same pref the
        // overlay's hide chip writes. Display only; default ON.
        val dotsSw = SgUi.styledSwitch(this, "Score dots").apply {
            isChecked = Prefs.scoreDots(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setScoreDots(this@MainActivity, checked)
                DebugLog.log(this@MainActivity, "UI score dots set to $checked")
            }
        }
        diagCard.addView(dotsSw)
        diagCard.addView(SgUi.hint(this, "Red dots count full-screen strikes toward a block (4); yellow dots count video-region points (8). They appear while an app is under suspicion or a count is above zero. The small ✕ beside the dots hides them too."))
        diagCard.addView(SgUi.divider(this))
        diagCard.addView(SgUi.secondaryButton(this, "Score graph") {
            startActivity(Intent(this@MainActivity, ScoreGraphActivity::class.java))
        })
        diagCard.addView(SgUi.secondaryButton(this, "View debug log") {
            startActivity(Intent(this@MainActivity, LogActivity::class.java))
        })
        val logRow = weightedRow()
        val exportBtn = SgUi.secondaryButton(this, "Export / share log") {
            LogExport.shareWithFeedback(this@MainActivity)
        }
        rowBtn(exportBtn); logRow.addView(exportBtn)
        val saveBtn = SgUi.secondaryButton(this, "Save log to file…") {
            startActivityForResult(LogExport.saveIntent(), LogExport.REQ_SAVE_LOG)
        }
        rowBtn(saveBtn, leftMarginDp = 10); logRow.addView(saveBtn)
        diagCard.addView(logRow)
        diagCard.addView(SgUi.divider(this))
        diagCard.addView(SgUi.secondaryButton(this, "TEST block (fires in ~3 s)") {
            Prefs.get(this@MainActivity).edit()
                .putLong("test_block_due", System.currentTimeMillis() + 3000).apply()
            DebugLog.log(this@MainActivity, "TEST block armed from UI")
            statusTv.text = "TEST block armed — switch to Chrome (or just stay here). Overlay + Home should fire in ~3s if Enforce is on."
        })
        diagCard.addView(SgUi.hint(this, "Switch to any app now — overlay + Home should fire if Enforce is on."))
        diagCard.addView(SgUi.secondaryButton(this, "CAPTURE-bench: show photo fullscreen") {
            Prefs.get(this@MainActivity).edit().putBoolean("capbench", true).apply()
            DebugLog.log(this@MainActivity, "CAPBENCH armed from UI")
            startActivity(Intent(this@MainActivity, PhotoActivity::class.java))
        })
        val clearBtn = SgUi.dangerButton(this, "Clear debug log + event log") {
            DebugLog.clear(this@MainActivity)
            Prefs.get(this@MainActivity).edit().remove("log").apply()
            refreshUi()
        }
        diagCard.addView(clearBtn)
        gatedViews.add(clearBtn)
        root.addView(diagCard)

        // ---- Event log ----
        val logCard = SgUi.card(this)
        logCard.addView(SgUi.cardTitle(this, "Event log"))
        logCard.addView(SgUi.body(this,
            "Latest first · warnings and blocks only — full detail is in the debug log.", 12.5f).apply {
            setPadding(0, 0, 0, SgUi.dp(this@MainActivity, 6))
        })
        logTv = SgUi.text(this, "", 12.5f, p.muted, mono = true)
        logCard.addView(logTv)
        root.addView(logCard)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    @Deprecated("plain-Activity PoC; no appcompat activity-result contracts")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LogExport.REQ_SAVE_LOG) {
            LogExport.handleSaveResult(this, resultCode, data)
        }
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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        if (::notifBtn.isInitialized) {
            notifBtn.text = if (notifAllowed())
                "3 ·  Notifications allowed — shield icon on"
            else
                "3 ·  Allow notifications (shield icon)"
        }
        val p = Prefs.get(this)
        val sb = StringBuilder()
        sb.append("Accessibility service: ").append(if (isServiceEnabled()) "ON" else "OFF (turn it on in Setup, step 1)").append("\n")
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
            rulesTv.text = rulesSummary()
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
        val log = Prefs.log(this)
        logTv.text = if (log.isBlank()) "(no events yet)" else log.split("\n").reversed().joinToString("\n")
        refreshLockUi()
    }

    /** The current rules, in plain language, generated live from the settings. */
    private fun rulesSummary(): String {
        val sb = StringBuilder()
        sb.append("How blocking works right now — ONE scene model (Falconsai) judges whole pictures, and a block needs proof on fresh frames, never just one:\n")
        sb.append("• Full screen: the whole screen reads 0.90+ → the block parks as pending (strike 1 of 4); each further fresh 0.90+ whole-screen read adds a strike, and the fourth fires it. Clean frames can never cancel a parked block — a clean frame is usually just the next post — it only expires after 10 s with nothing reading 0.30+. A whole picture is not a fragment, so no partner or witness is needed\n")
        sb.append("• Video region: the region is drawn from the last 3 seconds of screen motion combined, so it grows to cover a playing video's whole card within a couple of seconds and stays on it while the video is paused; the model judges that region on its own, as its own whole picture — its verdicts earn points instead: 0.90+ earns 2, 0.75+ earns 1, under 0.60 loses 1, and 8 points blocks. No witness is needed — in the offline bench no benign whole picture ever scored above 0.011\n")
        sb.append("• Suspicion mode: any single look that reads 0.30+ — full screen or video region — puts that app under suspicion for 15 s: checks run every 1 s and the video-region finder rides the same ~1 s capture rhythm (Android allows at most one screenshot per second, shared by both), so warm content is sampled densely instead of at the idle rate. The bars above do not move — suspicion buys sampling, never a block by itself\n")
        sb.append("• Tripwire: a second, smaller classifier also reads every frame (full screen and video region), but it can NEVER block, park, or score points — it only knocks. Because the scene model's verdicts are near-polarised (almost always near 0 or near 1), this older model's graded in-between readings serve as the thermometer: if it reads 0.30+ in suspicious apps (browsers, social, video, chat) or 0.50+ anywhere else, the app enters suspicion mode too, so the scene model looks more often. If suspicion then runs out without the scene model ever reading 0.30+, the tripwire is muted for that app for 60 s, so warm-but-innocent video cannot hold the phone on high alert\n")
        sb.append("• Testing (ground truth): while the debug overlay is on, a small GT button floats at the bottom corner — tap it the moment explicit content appears (it turns red) and tap again when it is gone. Each marked stretch is logged as GT START / GT END with the models' peak readings and timings, so test sessions can be scored exactly. It never affects blocking\n")
        sb.append("• App previews: the app previews in Recents are also checked; a hit there just sends you Home briefly — the launcher itself is never locked or timed out. A proven hit there also opens Paranoid mode for 5 minutes: while it runs, the Home screen is checked every second and ANY reading of 0.30+ from either model — not just proof-grade readings — sends you Home with the 3 s cover again, so the previews cannot simply be watched between covers. Other apps are unaffected, and the 5 minutes never extends unless explicit content is proven there again\n")
        if (Prefs.blockSecure(this)) {
            sb.append("• Incognito: a capture-proof browser window on ${Prefs.secureFrames(this)} frames blocks the mode itself\n")
        }
        sb.append("After a block the app is closed and timed out: ${Prefs.quarBaseSec(this)}s, doubling per repeat to a ${Prefs.quarMaxSec(this)}s cap; the level resets after ${Prefs.quarResetMs(this) / 60000} clean minutes. And for 3 minutes after any block, suspicion mode runs in EVERY app — checks every 1 s, the region finder on the same ~1 s capture rhythm — so switching apps buys no quiet window.\n")
        sb.append("Checks run every ${Prefs.riskyIntervalMs(this) / 1000}s in suspicious apps (browsers, social, video, chat), ${Prefs.normalIntervalMs(this) / 1000}s in other apps — Instagram counts as an other app — and every ${Prefs.systemIntervalMs(this) / 1000}s in system apps (dialer, clock, calculator, contacts: sanity checks only, and anything suspicious there restores full cadence at once); any suspicious frame bursts to maximum cadence for ${Prefs.attentionMs(this) / 1000}s. If this phone cannot sustain the chosen rate, ScreenGuard slows its idle checks on its own (and a 2-minute benchmark can measure the right level up front) — suspicion bursts always stay at full speed. Settings, system UI and ScreenGuard's own screens get a sanity check at the system rate — never more than 60 s apart — and can trip suspicion but can never be blocked; the launcher is checked (for Recents previews) but never locked.")
        return sb.toString()
    }

    /** v0.5.0: reflect the lock state into the settings UI (called on the 1.5 s refresh). */
    private fun refreshLockUi() {
        if (!::lockStatusTv.isInitialized) return
        val state = Prefs.lockState(this)
        val remain = (Prefs.lockDeadline(this) - System.currentTimeMillis()).coerceAtLeast(0)
        val mmss = "%d:%02d".format(remain / 60000, (remain / 1000) % 60)
        // Run 5 (presentation only): the two "Settings lock:" states
        // get the labelValue emphasis (muted label, bold accent value);
        // the plain strings are byte-identical. The timer/open lines
        // keep their plain form.
        val lockText = when (state) {
            Prefs.LOCK_UNLOCKED -> "Settings lock: OFF"
            Prefs.LOCK_LOCKED -> "Settings lock: ON — protection settings are frozen"
            Prefs.LOCK_TIMER -> "Unlock requested — settings open in $mmss (a block cancels it)"
            else -> "Settings OPEN for $mmss — then they re-lock"
        }
        lockStatusTv.text = if (lockText.startsWith("Settings lock: ")) {
            SgUi.labelValue(this, "Settings lock", lockText.removePrefix("Settings lock: "))
        } else lockText
        val editable = state == Prefs.LOCK_UNLOCKED || state == Prefs.LOCK_OPEN
        gatedViews.forEach { it.isEnabled = editable }
        lockSw.isChecked = state != Prefs.LOCK_UNLOCKED
        lockSw.isEnabled = state == Prefs.LOCK_UNLOCKED || state == Prefs.LOCK_OPEN
        unlockBtn.isEnabled = state == Prefs.LOCK_LOCKED
        cancelUnlockBtn.isEnabled = state == Prefs.LOCK_TIMER
    }

}
