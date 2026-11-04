package com.rem.screenguard

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Full-screen viewer for the verbose debug log, with share + clear.
 *
 * UI-polish Run 2 (Oct 28, 2026): presentation only — SgUi console
 * surface, actions in a contained 2×2 toolbar (the old single row of
 * four stock buttons overflowed narrow widths). Actions, the
 * auto-scroll-to-bottom and this screen's NOT-lock-gated Clear are
 * unchanged from v0.11.14.
 */
class LogActivity : Activity() {

    private lateinit var logTv: TextView
    private lateinit var scroll: ScrollView

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
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        root.addView(SgUi.text(this, "Debug log", 24f, p.text, bold = true))
        root.addView(SgUi.hint(this, "Full verbose log · newest at the bottom."))

        fun row(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun cell(b: Button, left: Int = 0) {
            b.textSize = 14f
            b.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                topMargin = dp(8); leftMargin = dp(left)
            }
        }
        val r1 = row()
        val refreshBtn = SgUi.secondaryButton(this, "Refresh") { show() }
        cell(refreshBtn); r1.addView(refreshBtn)
        val shareBtn = SgUi.secondaryButton(this, "Share log") { share() }
        cell(shareBtn, 10); r1.addView(shareBtn)
        root.addView(r1)
        val r2 = row()
        val saveBtn = SgUi.secondaryButton(this, "Save to file") {
            startActivityForResult(LogExport.saveIntent(), LogExport.REQ_SAVE_LOG)
        }
        cell(saveBtn); r2.addView(saveBtn)
        val clearBtn = SgUi.dangerButton(this, "Clear") {
            DebugLog.clear(this@LogActivity); show()
        }
        cell(clearBtn, 10); r2.addView(clearBtn)
        root.addView(r2)

        val console = SgUi.card(this, topMarginDp = 14).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(14) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        logTv = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(p.muted)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(logTv)
        }
        console.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(console)
        setContentView(root)
        show()
    }

    private fun show() {
        val t = DebugLog.text(this)
        if (t.isBlank()) {
            // Run 4: the old empty message was a long parenthetical
            // sentence in monospace — a prose wall in miniature.
            logTv.text = "(debug log is empty)\nTurn on the service and verbose logging, then Refresh."
        } else {
            // Run 3: FAIL-line tinting — spans only, the log text itself
            // is byte-identical (console pattern: failures read first).
            val p = SgUi.palette(this)
            val ss = android.text.SpannableString(t)
            var start = 0
            while (start < t.length) {
                var end = t.indexOf('\n', start)
                if (end < 0) end = t.length
                if (t.substring(start, end).contains("FAIL ")) {
                    ss.setSpan(android.text.style.ForegroundColorSpan(p.danger),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                start = end + 1
            }
            logTv.text = ss
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun share() {
        // v0.2.1/v0.2.2: export the log as a FILE to Downloads and share the
        // file, with a visible success/failure Toast (no silent fallbacks).
        LogExport.shareWithFeedback(this)
    }

    @Deprecated("plain-Activity PoC; no appcompat activity-result contracts")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LogExport.REQ_SAVE_LOG) {
            LogExport.handleSaveResult(this, resultCode, data)
            show()
        }
    }
}
