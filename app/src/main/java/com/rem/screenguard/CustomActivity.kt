package com.rem.screenguard

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Custom fine-tuning screen (Alejandro): the counts, windows and
 * timers the guard uses, grouped by what they do, each with a
 * plain-language explanation. Editing anything marks the protection
 * level "Custom". Frozen by the settings lock, exactly like the Home
 * controls.
 * v0.11.0: the per-model slider groups (detector bars, classifier
 * bars, weak-window rows) are REMOVED — their models and machinery
 * are gone, and the one model's score bars are fixed constants, not
 * settings (see GuardService). What remains is everything that still
 * exists: corroboration counts/windows, the incognito frame count
 * and cadence. (The cooldown ladder left this screen in v0.15.1 —
 * quarantine duration is code-only behaviour, shown read-only.)
 * Fixed safety rules (NOT adjustable here): a block always needs
 * proof on more than one fresh frame; the launcher is checked for
 * Recents previews but never locked; Settings is sanity-checked
 * (v0.14.5) but can never be blocked.
 *
 * UI-polish Run 2 (Oct 28, 2026): presentation rebuilt on the SgUi
 * tokens — grouped cards, label + live value on one contained line,
 * each hint as its own small line under its slider (never appended
 * to the label: the overflow defect). Keys, ranges, steps, the
 * fromUser-only write, changed() and the gated SeekBar sweep are
 * unchanged from v0.11.14.
 */
class CustomActivity : Activity() {

    private val gated = mutableListOf<SeekBar>()
    private lateinit var levelTv: TextView

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
        var card: LinearLayout? = null
        fun header(title: String, expl: String) {
            val c = SgUi.card(this@CustomActivity, topMarginDp = 14)
            c.addView(SgUi.cardTitle(this@CustomActivity, title))
            c.addView(SgUi.body(this@CustomActivity, expl, 13f))
            root.addView(c)
            card = c
        }
        fun changed() {
            Prefs.markCustom(this@CustomActivity)
            // Run 4: same emphasis as the slider values (Run 3) —
            // plain string identical, value bold + accent via spans.
            levelTv.text = SgUi.labelValue(this@CustomActivity, "Protection level", "Custom (hand-tuned)")
        }
        fun addRow(lbl: TextView, sb: SeekBar, hint: String) {
            val c = card ?: return
            c.addView(SgUi.divider(this@CustomActivity))
            c.addView(lbl)
            c.addView(sb)
            if (hint.isNotBlank()) c.addView(SgUi.hint(this@CustomActivity, hint))
            gated.add(sb)
        }
        fun floatRow(label: String, key: String, min: Float, max: Float, initial: Float, hint: String) {
            val lbl = SgUi.text(this, "", 15f, p.text)
            val sb = SgUi.styledSeek(this).apply { this.max = ((max - min) * 100).toInt() }
            sb.progress = ((initial - min) * 100).toInt()
            lbl.text = SgUi.labelValue(this, label, "%.2f".format(initial))
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + pr / 100f
                    Prefs.get(this@CustomActivity).edit().putFloat(key, v).apply()
                    lbl.text = SgUi.labelValue(this@CustomActivity, label, "%.2f".format(v))
                    changed()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            addRow(lbl, sb, hint)
        }
        fun intRow(label: String, key: String, min: Int, max: Int, initial: Int, unit: String, hint: String) {
            val lbl = SgUi.text(this, "", 15f, p.text)
            val sb = SgUi.styledSeek(this).apply { this.max = max - min }
            sb.progress = initial - min
            lbl.text = SgUi.labelValue(this, label, "$initial$unit")
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + pr
                    Prefs.get(this@CustomActivity).edit().putInt(key, v).apply()
                    lbl.text = SgUi.labelValue(this@CustomActivity, label, "$v$unit")
                    changed()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            addRow(lbl, sb, hint)
        }
        fun secondsRow(label: String, key: String, min: Int, max: Int, initialSec: Int, hint: String) {
            val lbl = SgUi.text(this, "", 15f, p.text)
            val sb = SgUi.styledSeek(this).apply { this.max = max - min }
            sb.progress = initialSec - min
            lbl.text = SgUi.labelValue(this, label, "${initialSec}s")
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + pr
                    Prefs.get(this@CustomActivity).edit().putLong(key, v * 1000L).apply()
                    lbl.text = SgUi.labelValue(this@CustomActivity, label, "${v}s")
                    changed()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            addRow(lbl, sb, hint)
        }

        root.addView(SgUi.text(this, "Custom fine-tuning", 26f, p.text, bold = true))
        root.addView(SgUi.body(this,
            "Counts, windows and timers. Changes apply immediately.\n" +
                "Frozen while the settings lock is on.", 13.5f).apply {
            setPadding(0, dp(4), 0, 0)
        })
        levelTv = SgUi.text(this, "", 17f, p.text, bold = true).apply {
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(levelTv)
        root.addView(SgUi.secondaryButton(this, "Reset to the Balanced preset") {
            Prefs.applyPreset(this@CustomActivity, "balanced")
            DebugLog.log(this@CustomActivity, "UI preset applied: balanced (from Custom screen)")
            recreate()
        })

        header("Corroboration — proof a block needs",
            "Nothing blocks on one frame. A 0.90 whole-screen score parks a block " +
                "(strike 1 of 4); the fourth fresh 0.90 fires it. Region verdicts earn points (0.90+ = 2, " +
                "0.75+ = 1, under 0.60 = −1; 8 points blocks). Fixed by design — no sliders.")

        header("Incognito",
            "Capture-proof browser windows read as black. The guard blocks the mode itself after this many such frames.")
        intRow("Incognito frames to block", "secure_frames_needed", 1, 4, Prefs.secureFrames(this), " frames", "")

        header("Cadence & battery",
            "Idle check rates. Any suspicious frame bursts to maximum cadence at once, so idle rates cost no protection.")
        floatRow("Suspicion: scene-model warmth", "susp_fal", 0.10f, 0.80f, Prefs.suspFal(this), "Either pass at this starts the burst.")
        intRow("Attention burst", "attention_s", 1, 15, (Prefs.attentionMs(this) / 1000L).toInt(), "s", "Max cadence after a suspicious frame.")
        // v0.13.0: rows renamed to their tiers (app-tiers design §5)
        // + the third cadence — the SYSTEM sanity interval.
        secondsRow("Suspicious-app check interval", "risky_ms", 1, 30, (Prefs.riskyIntervalMs(this) / 1000L).toInt(), "SUSPICIOUS tier: browsers, social, video, chat.")
        secondsRow("Other-app check interval", "normal_ms", 3, 60, (Prefs.normalIntervalMs(this) / 1000L).toInt(), "NORMAL tier: everywhere else, Instagram included.")
        secondsRow("System sanity interval", "system_ms", 10, 60, (Prefs.systemIntervalMs(this) / 1000L).toInt(), "SYSTEM tier + exempt sanity: dialer, clock, calculator, contacts — and the sanity check on exempt screens. Capped at 60 s (v0.14.5): nothing goes unscanned longer.")

        // v0.13.0: read-only tier membership (editing is not in v1 —
        // a wrong hand edit here could exempt exactly the wrong app).
        header("App tiers",
            "SUSPICIOUS — Chrome, Firefox, Brave, X, TikTok, Reddit, Snapchat, Telegram, Discord, YouTube.\n" +
                "SYSTEM — the stock dialer, clock, calculator and contacts apps.\n" +
                "NORMAL — everything else, including Instagram, Photos/Gallery and the launcher.\n" +
                "EXEMPT — Settings, system UI and ScreenGuard itself are sanity-checked at the system cadence (v0.14.5): they can trip suspicion, never be blocked.\n" +
                "Membership is fixed in this version. Tiers change idle rates only — suspicion always bursts to full speed.")

        // v0.15.1: the cooldown controls are GONE — quarantine duration
        // is app behaviour, not a setting (Alejandro's correction). The
        // card stays as read-only transparency: the fixed ladder,
        // stated in full, with no sliders.
        header("Cooldown — penalty after a block",
            "A blocked app is closed and timed out: 10 s for a first block, doubling per repeat (20 s, 40 s, 80 s, 160 s, then 320 s) up to a 600 s cap; the level resets after 30 clean minutes. Fixed by design — the quarantine length is built into the app, so there are no sliders here.")

        root.addView(SgUi.primaryButton(this, "Back") { finish() })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(root)
        }
        setContentView(scroll)
        refreshLevel()
    }

    override fun onResume() {
        super.onResume()
        refreshLevel()
    }

    private fun refreshLevel() {
        val lvl = Prefs.protectionLevel(this)
        // Run 4: labelValue spans — plain string identical to before.
        levelTv.text = SgUi.labelValue(this, "Protection level", when (lvl) {
            "gentle" -> "Gentle"
            "strict" -> "Strict"
            "custom" -> "Custom (hand-tuned)"
            else -> "Balanced"
        })
        val state = Prefs.lockState(this)
        val editable = state == Prefs.LOCK_UNLOCKED || state == Prefs.LOCK_OPEN
        gated.forEach { it.isEnabled = editable }
    }
}
