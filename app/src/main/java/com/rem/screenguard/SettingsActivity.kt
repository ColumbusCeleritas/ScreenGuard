package com.rem.screenguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * v0.19.8: the Settings screen (menu restructure,
 * menu-battery-sus-design-2026-11-14, placement map rows 10–14).
 * Hosts, in order: the settings-lock card, the Protection toggles,
 * Diagnostics, Appearance, and Privacy & open source — moved from
 * Home with their wiring and texts verbatim. The settings-lock
 * freeze semantics are preserved exactly: this screen's gated set
 * (Enforce, Block incognito, Clear log) freezes under the same
 * `editable = UNLOCKED || OPEN` rule Home applied, driven by the
 * same 1.5 s refresh pattern (refreshLockUi below is the shipped
 * logic, moved). The remaining gated views (the level buttons and
 * the Setup step-1 button) stay on Home under Home's own freeze.
 *
 * One deliberate change, per the design: the TEST block's feedback
 * line — it wrote into Home's statusTv, which no longer shares a
 * screen with the button — is now a Toast with the same wording.
 * The armed pref and the DebugLog line are unchanged.
 */
class SettingsActivity : Activity() {

    private lateinit var lockStatusTv: TextView
    private lateinit var lockSw: Switch
    private lateinit var unlockBtn: Button
    private lateinit var cancelUnlockBtn: Button
    private lateinit var themeTv: TextView
    private val gatedViews = mutableListOf<View>()
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(20), dp(26), dp(20), dp(28))
        }
        root.addView(SgUi.text(this, "Settings", 26f, p.text, bold = true))

        // ---- Settings lock (v0.5.0 semantics preserved verbatim) ----
        val lockCard = SgUi.card(this, topMarginDp = 18)
        lockCard.addView(SgUi.cardTitle(this, "Settings lock"))
        lockStatusTv = SgUi.text(this, "", 15.5f, p.text, bold = true)
        lockCard.addView(lockStatusTv)
        lockSw = SgUi.styledSwitch(this, "Lock settings").apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    Prefs.lockNow(this@SettingsActivity)
                    DebugLog.log(this@SettingsActivity, "UI settings LOCKED")
                } else if (Prefs.lockState(this@SettingsActivity) == Prefs.LOCK_OPEN) {
                    Prefs.unlockFully(this@SettingsActivity)
                    DebugLog.log(this@SettingsActivity, "UI settings unlocked (through the open window)")
                }
                refreshLockUi()
            }
        }
        lockCard.addView(lockSw)
        lockCard.addView(SgUi.hint(this, "Changes then need the unlock ritual: prayer + wait."))
        val lockRow = weightedRow()
        unlockBtn = SgUi.secondaryButton(this, "Request unlock…") {
            startActivity(Intent(this@SettingsActivity, UnlockActivity::class.java))
        }
        rowBtn(unlockBtn)
        lockRow.addView(unlockBtn)
        cancelUnlockBtn = SgUi.secondaryButton(this, "Cancel request") {
            Prefs.cancelUnlock(this@SettingsActivity)
            DebugLog.log(this@SettingsActivity, "UNLOCK request cancelled by user")
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
            isChecked = Prefs.enforceMode(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@SettingsActivity).edit().putBoolean("enforce", checked).apply()
            }
        }
        togglesCard.addView(enforceSw)
        togglesCard.addView(SgUi.hint(this, "Quarantine the app on detection. Off = warn only."))
        gatedViews.add(enforceSw)
        togglesCard.addView(SgUi.divider(this))
        val secureSw = SgUi.styledSwitch(this, "Block incognito / secure windows").apply {
            isChecked = Prefs.blockSecure(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@SettingsActivity).edit().putBoolean("block_secure", checked).apply()
            }
        }
        togglesCard.addView(secureSw)
        togglesCard.addView(SgUi.hint(this, "Capture-proof screens in browsers."))
        gatedViews.add(secureSw)
        root.addView(togglesCard)

        // ---- Diagnostics (deliberately NOT lock-gated, as before —
        // only its Clear button is in the gated set) ----
        val diagCard = SgUi.card(this)
        diagCard.addView(SgUi.cardTitle(this, "Diagnostics"))
        val verboseSw = SgUi.styledSwitch(this, "Verbose debug log").apply {
            isChecked = Prefs.verbose(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@SettingsActivity).edit().putBoolean("verbose", checked).apply()
                DebugLog.log(this@SettingsActivity, "UI verbose set to $checked")
            }
        }
        diagCard.addView(verboseSw)
        diagCard.addView(SgUi.hint(this, "Every capture, diff and score."))
        val debugSw = SgUi.styledSwitch(this, "Debug overlay").apply {
            isChecked = Prefs.debugOverlay(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@SettingsActivity).edit().putBoolean("debug_overlay", checked).apply()
                DebugLog.log(this@SettingsActivity, "UI debug overlay set to $checked")
            }
        }
        diagCard.addView(debugSw)
        diagCard.addView(SgUi.hint(this, "Live model scores on screen, small, top-left."))
        val dotsSw = SgUi.styledSwitch(this, "Score dots").apply {
            isChecked = Prefs.scoreDots(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setScoreDots(this@SettingsActivity, checked)
                DebugLog.log(this@SettingsActivity, "UI score dots set to $checked")
            }
        }
        diagCard.addView(dotsSw)
        diagCard.addView(SgUi.hint(this, "Red dots count full-screen strikes toward a block (4); yellow dots count video-region points (8). They appear while an app is under suspicion or a count is above zero. The small ✕ beside the dots hides them too."))
        diagCard.addView(SgUi.divider(this))
        diagCard.addView(SgUi.secondaryButton(this, "Score graph") {
            startActivity(Intent(this@SettingsActivity, ScoreGraphActivity::class.java))
        })
        diagCard.addView(SgUi.secondaryButton(this, "View debug log") {
            startActivity(Intent(this@SettingsActivity, LogActivity::class.java))
        })
        val logRow = weightedRow()
        val exportBtn = SgUi.secondaryButton(this, "Export / share log") {
            LogExport.shareWithFeedback(this@SettingsActivity)
        }
        rowBtn(exportBtn); logRow.addView(exportBtn)
        val saveBtn = SgUi.secondaryButton(this, "Save log to file…") {
            startActivityForResult(LogExport.saveIntent(), LogExport.REQ_SAVE_LOG)
        }
        rowBtn(saveBtn, leftMarginDp = 10); logRow.addView(saveBtn)
        diagCard.addView(logRow)
        diagCard.addView(SgUi.divider(this))
        diagCard.addView(SgUi.secondaryButton(this, "TEST block (fires in ~3 s)") {
            Prefs.get(this@SettingsActivity).edit()
                .putLong("test_block_due", System.currentTimeMillis() + 3000).apply()
            DebugLog.log(this@SettingsActivity, "TEST block armed from UI")
            Toast.makeText(
                this@SettingsActivity,
                "TEST block armed — switch to Chrome (or just stay here). Overlay + Home should fire in ~3s if Enforce is on.",
                Toast.LENGTH_LONG
            ).show()
        })
        diagCard.addView(SgUi.hint(this, "Switch to any app now — overlay + Home should fire if Enforce is on."))
        diagCard.addView(SgUi.secondaryButton(this, "CAPTURE-bench: show photo fullscreen") {
            Prefs.get(this@SettingsActivity).edit().putBoolean("capbench", true).apply()
            DebugLog.log(this@SettingsActivity, "CAPBENCH armed from UI")
            startActivity(Intent(this@SettingsActivity, PhotoActivity::class.java))
        })
        val clearBtn = SgUi.dangerButton(this, "Clear debug log + event log") {
            DebugLog.clear(this@SettingsActivity)
            Prefs.get(this@SettingsActivity).edit().remove("log").apply()
            refreshUi()
        }
        diagCard.addView(clearBtn)
        gatedViews.add(clearBtn)
        root.addView(diagCard)

        // ---- Appearance & Supporter Pack (v0.19.0, play-iap) ----
        val themeCard = SgUi.card(this)
        themeCard.addView(SgUi.cardTitle(this, "Appearance"))
        themeTv = SgUi.text(this, "", 15f, p.text)
        themeCard.addView(themeTv)
        themeCard.addView(SgUi.body(this,
            "Accent themes for these screens. Protection is free and identical for everyone.", 13.5f).apply {
            setPadding(0, dp(6), 0, 0)
        })
        themeCard.addView(SgUi.secondaryButton(this, "Themes…") {
            startActivity(Intent(this@SettingsActivity, ThemesActivity::class.java))
        })
        root.addView(themeCard)

        // ---- Privacy & open source (Play G3 remainder) ----
        val infoCard = SgUi.card(this)
        infoCard.addView(SgUi.cardTitle(this, "Privacy & open source"))
        infoCard.addView(SgUi.body(this,
            "ScreenGuard collects nothing and sends nothing anywhere. " +
                "The full privacy policy is readable inside the app, and " +
                "ScreenGuard is free software under GPL-3.0 — its licence " +
                "texts and third-party notices are included too."))
        infoCard.addView(SgUi.secondaryButton(this, "Privacy policy") {
            startActivity(Intent(this@SettingsActivity, TextInfoActivity::class.java)
                .putExtra(TextInfoActivity.EXTRA_KIND, TextInfoActivity.KIND_PRIVACY))
        })
        infoCard.addView(SgUi.secondaryButton(this, "Open-source licences") {
            startActivity(Intent(this@SettingsActivity, TextInfoActivity::class.java)
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

    override fun onResume() {
        super.onResume()
        if (Prefs.themeKey(this) != createdTheme) { recreate(); return }
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    @Deprecated("plain-Activity PoC; no appcompat activity-result contracts")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LogExport.REQ_SAVE_LOG) {
            LogExport.handleSaveResult(this, resultCode, data)
        }
    }

    private fun refreshUi() {
        if (::themeTv.isInitialized) {
            val theme = SgTheme.current(this)
            themeTv.text = SgUi.labelValue(
                this, "Theme",
                theme.name + if (SupporterGate.entitled(this)) " · Supporter Pack owned" else ""
            )
        }
        refreshLockUi()
    }

    /** v0.5.0: reflect the lock state into the settings UI — the
     *  shipped refreshLockUi logic, moved with the lock card. */
    private fun refreshLockUi() {
        if (!::lockStatusTv.isInitialized) return
        val state = Prefs.lockState(this)
        val remain = (Prefs.lockDeadline(this) - System.currentTimeMillis()).coerceAtLeast(0)
        val mmss = "%d:%02d".format(remain / 60000, (remain / 1000) % 60)
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
