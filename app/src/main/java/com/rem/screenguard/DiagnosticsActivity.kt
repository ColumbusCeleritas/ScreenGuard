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
import android.widget.Toast

/**
 * v0.19.9 (UI flattening, Alejandro's order): the Diagnostics
 * screen. The Settings screen is abolished — the menu is the
 * settings list — and the Diagnostics card that lived inside it
 * becomes its own top-level screen, wiring verbatim: the three
 * diagnostic switches, the score graph / debug-log viewers, log
 * export + save-to-file (with the Save-log activity-result
 * handler, moved with its button), the TEST block (Toast
 * feedback, same wording as v0.19.8), the CAPTURE-bench entry,
 * and Clear log. As before, Diagnostics is deliberately NOT
 * lock-gated — only its Clear button belongs to the gated set,
 * and it freezes here under the same `editable = UNLOCKED ||
 * OPEN` rule on the same 1.5 s refresh the Settings screen ran.
 */
class DiagnosticsActivity : Activity() {

    private val gatedViews = mutableListOf<View>()
    private var createdTheme: String = ""

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshLockUi(); ui.postDelayed(this, 1500) }
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
        root.addView(SgUi.screenTitle(this, "Diagnostics"))

        // ---- Diagnostics (deliberately NOT lock-gated, as before —
        // only its Clear button is in the gated set) ----
        val diagCard = SgUi.card(this, topMarginDp = 18)
        val verboseSw = SgUi.styledSwitch(this, "Verbose debug log").apply {
            isChecked = Prefs.verbose(this@DiagnosticsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@DiagnosticsActivity).edit().putBoolean("verbose", checked).apply()
                DebugLog.log(this@DiagnosticsActivity, "UI verbose set to $checked")
            }
        }
        diagCard.addView(verboseSw)
        diagCard.addView(SgUi.hint(this, "Every capture, diff and score."))
        val debugSw = SgUi.styledSwitch(this, "Debug overlay").apply {
            isChecked = Prefs.debugOverlay(this@DiagnosticsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.get(this@DiagnosticsActivity).edit().putBoolean("debug_overlay", checked).apply()
                DebugLog.log(this@DiagnosticsActivity, "UI debug overlay set to $checked")
            }
        }
        diagCard.addView(debugSw)
        diagCard.addView(SgUi.hint(this, "Live model scores on screen, small, top-left."))
        val dotsSw = SgUi.styledSwitch(this, "Score dots").apply {
            isChecked = Prefs.scoreDots(this@DiagnosticsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setScoreDots(this@DiagnosticsActivity, checked)
                DebugLog.log(this@DiagnosticsActivity, "UI score dots set to $checked")
            }
        }
        diagCard.addView(dotsSw)
        diagCard.addView(SgUi.hint(this, "Red dots count full-screen strikes toward a block (4); yellow dots count video-region points (8). They appear while an app is under suspicion or a count is above zero. The small ✕ beside the dots hides them too."))
        diagCard.addView(SgUi.divider(this))
        diagCard.addView(SgUi.secondaryButton(this, "Score graph") {
            startActivity(Intent(this@DiagnosticsActivity, ScoreGraphActivity::class.java))
        })
        diagCard.addView(SgUi.secondaryButton(this, "View debug log") {
            startActivity(Intent(this@DiagnosticsActivity, LogActivity::class.java))
        })
        val logRow = weightedRow()
        val exportBtn = SgUi.secondaryButton(this, "Export / share log") {
            LogExport.shareWithFeedback(this@DiagnosticsActivity)
        }
        rowBtn(exportBtn); logRow.addView(exportBtn)
        val saveBtn = SgUi.secondaryButton(this, "Save log to file…") {
            startActivityForResult(LogExport.saveIntent(), LogExport.REQ_SAVE_LOG)
        }
        rowBtn(saveBtn, leftMarginDp = 10); logRow.addView(saveBtn)
        diagCard.addView(logRow)
        diagCard.addView(SgUi.divider(this))
        diagCard.addView(SgUi.secondaryButton(this, "TEST block (fires in ~3 s)") {
            Prefs.get(this@DiagnosticsActivity).edit()
                .putLong("test_block_due", System.currentTimeMillis() + 3000).apply()
            DebugLog.log(this@DiagnosticsActivity, "TEST block armed from UI")
            Toast.makeText(
                this@DiagnosticsActivity,
                "TEST block armed — switch to Chrome (or just stay here). Overlay + Home should fire in ~3s if Enforce is on.",
                Toast.LENGTH_LONG
            ).show()
        })
        diagCard.addView(SgUi.hint(this, "Switch to any app now — overlay + Home should fire if Enforce is on."))
        diagCard.addView(SgUi.secondaryButton(this, "CAPTURE-bench: show photo fullscreen") {
            Prefs.get(this@DiagnosticsActivity).edit().putBoolean("capbench", true).apply()
            DebugLog.log(this@DiagnosticsActivity, "CAPBENCH armed from UI")
            startActivity(Intent(this@DiagnosticsActivity, PhotoActivity::class.java))
        })
        val clearBtn = SgUi.dangerButton(this, "Clear debug log + event log") {
            DebugLog.clear(this@DiagnosticsActivity)
            Prefs.get(this@DiagnosticsActivity).edit().remove("log").apply()
            refreshLockUi()
        }
        diagCard.addView(clearBtn)
        gatedViews.add(clearBtn)
        root.addView(diagCard)

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

    /** v0.5.0: the settings-lock freeze for the one gated view
     *  this screen hosts (Clear log) — the same rule the Settings
     *  screen applied to its gated set. */
    private fun refreshLockUi() {
        val state = Prefs.lockState(this)
        val editable = state == Prefs.LOCK_UNLOCKED || state == Prefs.LOCK_OPEN
        gatedViews.forEach { it.isEnabled = editable }
    }
}
