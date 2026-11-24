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

/**
 * v0.19.9 (UI flattening, Alejandro's order): the Settings lock
 * screen. The Settings screen is abolished — the menu is the
 * settings list — and the settings-lock card that lived at its
 * top becomes its own top-level screen, wiring verbatim
 * (v0.5.0 semantics preserved exactly). The lock state machine
 * (Prefs) is unchanged and shared: the gated views themselves
 * now live on Home (Setup step-1 button), Protection (level
 * buttons + toggles), and Diagnostics (Clear log), and each of
 * those screens freezes its own gated set under the same
 * `editable = UNLOCKED || OPEN` rule on the same 1.5 s refresh.
 * This screen only reflects and drives the lock state itself.
 */
class LockActivity : Activity() {

    private lateinit var lockStatusTv: TextView
    private lateinit var lockSw: Switch
    private lateinit var unlockBtn: Button
    private lateinit var cancelUnlockBtn: Button
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
        root.addView(SgUi.screenTitle(this, "Settings lock"))

        // ---- Settings lock (v0.5.0 semantics preserved verbatim) ----
        val lockCard = SgUi.card(this, topMarginDp = 18)
        lockStatusTv = SgUi.text(this, "", 15.5f, p.text, bold = true)
        lockCard.addView(lockStatusTv)
        lockSw = SgUi.styledSwitch(this, "Lock settings").apply {
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    Prefs.lockNow(this@LockActivity)
                    DebugLog.log(this@LockActivity, "UI settings LOCKED")
                } else if (Prefs.lockState(this@LockActivity) == Prefs.LOCK_OPEN) {
                    Prefs.unlockFully(this@LockActivity)
                    DebugLog.log(this@LockActivity, "UI settings unlocked (through the open window)")
                }
                refreshLockUi()
            }
        }
        lockCard.addView(lockSw)
        lockCard.addView(SgUi.hint(this, "Changes then need the unlock ritual: prayer + wait."))
        val lockRow = weightedRow()
        unlockBtn = SgUi.secondaryButton(this, "Request unlock…") {
            startActivity(Intent(this@LockActivity, UnlockActivity::class.java))
        }
        rowBtn(unlockBtn)
        lockRow.addView(unlockBtn)
        cancelUnlockBtn = SgUi.secondaryButton(this, "Cancel request") {
            Prefs.cancelUnlock(this@LockActivity)
            DebugLog.log(this@LockActivity, "UNLOCK request cancelled by user")
            refreshLockUi()
        }
        rowBtn(cancelUnlockBtn, leftMarginDp = 10)
        lockRow.addView(cancelUnlockBtn)
        lockCard.addView(lockRow)
        root.addView(lockCard)

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

    /** v0.5.0: reflect the lock state into the lock UI — the
     *  shipped refreshLockUi logic, moved with the lock card. (The
     *  gated-set freeze the same function used to apply here now
     *  runs on the screens that host the gated views.) */
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
        lockSw.isChecked = state != Prefs.LOCK_UNLOCKED
        lockSw.isEnabled = state == Prefs.LOCK_UNLOCKED || state == Prefs.LOCK_OPEN
        unlockBtn.isEnabled = state == Prefs.LOCK_LOCKED
        cancelUnlockBtn.isEnabled = state == Prefs.LOCK_TIMER
    }
}
