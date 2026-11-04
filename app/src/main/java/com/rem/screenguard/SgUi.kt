package com.rem.screenguard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * UI-polish Run 1 (Oct 28, 2026): shared design tokens + view helpers.
 * PRESENTATION ONLY — no behaviour, no prefs, no logic lives here.
 * Dark-first: deep ink-navy surfaces, one sky accent, rounded cards.
 * The light palette uses the same tokens on light surfaces.
 */
object SgUi {

    data class Palette(
        val bg: Int, val surface: Int, val surface2: Int, val stroke: Int,
        val text: Int, val muted: Int, val faint: Int,
        val accent: Int, val accentText: Int,
        val success: Int, val warning: Int, val danger: Int
    )

    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun palette(ctx: Context): Palette = if (isDark(ctx)) Palette(
        bg = Color.rgb(11, 18, 32),          // ink navy
        surface = Color.rgb(22, 32, 51),
        surface2 = Color.rgb(30, 42, 64),
        stroke = Color.rgb(46, 61, 86),
        text = Color.rgb(230, 237, 247),
        muted = Color.rgb(154, 168, 191),
        faint = Color.rgb(110, 124, 148),
        accent = Color.rgb(56, 189, 248),    // sky / Rem blue
        accentText = Color.rgb(8, 24, 40),
        success = Color.rgb(52, 211, 153),
        warning = Color.rgb(251, 191, 36),
        danger = Color.rgb(248, 113, 113)
    ) else Palette(
        bg = Color.rgb(242, 245, 249),
        surface = Color.rgb(255, 255, 255),
        surface2 = Color.rgb(233, 238, 245),
        stroke = Color.rgb(211, 219, 231),
        text = Color.rgb(15, 27, 46),
        muted = Color.rgb(91, 107, 130),
        faint = Color.rgb(133, 148, 168),
        accent = Color.rgb(2, 132, 199),
        accentText = Color.rgb(255, 255, 255),
        success = Color.rgb(5, 150, 105),
        warning = Color.rgb(180, 120, 10),
        danger = Color.rgb(200, 40, 40)
    )

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
        }

    /** A vertical card the caller fills; add it to a vertical parent. */
    fun card(ctx: Context, topMarginDp: Int = 14): LinearLayout {
        val p = palette(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx, p.surface, 18, p.stroke)
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, topMarginDp) }
        }
    }

    fun text(
        ctx: Context, s: String, sp: Float, color: Int,
        bold: Boolean = false, mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        text = s; textSize = sp; setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE
        else if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.22f)
    }

    /** Small caps-style card heading in the accent colour. */
    fun cardTitle(ctx: Context, s: String): TextView =
        text(ctx, s.uppercase(), 12.5f, palette(ctx).accent, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(ctx, 8))
        }

    fun body(ctx: Context, s: String, sp: Float = 14f): TextView =
        text(ctx, s, sp, palette(ctx).muted)

    /** Run 3: "Label: value" with the value emphasised (bold, accent) —
     *  the 1Password / M3-Expressive emphasized-type pattern. The plain
     *  string is byte-identical to the unstyled form; only spans differ. */
    fun labelValue(ctx: Context, label: String, value: String): CharSequence {
        val p = palette(ctx)
        val full = "$label: $value"
        val ss = android.text.SpannableString(full)
        val st = label.length + 2
        ss.setSpan(android.text.style.ForegroundColorSpan(p.accent), st, full.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ss.setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, full.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    /** Run 3: hero status styling — the status TEXT is unchanged (the
     *  refreshUi contract); spans only mute each line's "Label:" prefix
     *  and colour the service / FAL state token (ON/ARMED = success,
     *  OFF/LATCHED OFF = danger), the glanceable-status pattern.
     *  Run 4: multi-counter lines ("Frames analysed: 0   Blocks: 0   …",
     *  separated by three spaces in the contract text) get the same
     *  treatment per segment — every label muted, every counter bold —
     *  and the Loop line's numbers are bolded, so the counters scan as
     *  numbers, not as a sentence. Spans only; text byte-identical. */
    fun styleStatus(ctx: Context, raw: String): CharSequence {
        val p = palette(ctx)
        val ss = android.text.SpannableString(raw)
        var lineStart = 0
        while (lineStart < raw.length) {
            var lineEnd = raw.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = raw.length
            // Per-segment label muting (segments split on three spaces).
            var segStart = lineStart
            while (segStart < lineEnd) {
                var segEnd = raw.indexOf("   ", segStart)
                if (segEnd < 0 || segEnd > lineEnd) segEnd = lineEnd
                val colon = raw.indexOf(':', segStart)
                if (colon in segStart until segEnd) {
                    ss.setSpan(android.text.style.ForegroundColorSpan(p.muted),
                        segStart, colon + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // Bold the value token right after "Label: ".
                    val vStart = colon + 2
                    if (vStart < segEnd) {
                        var vEnd = raw.indexOf(' ', vStart)
                        if (vEnd < 0 || vEnd > segEnd) vEnd = segEnd
                        if (vEnd > vStart && raw.substring(vStart, vEnd).any { it.isDigit() }) {
                            ss.setSpan(android.text.style.StyleSpan(Typeface.BOLD),
                                vStart, vEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                segStart = segEnd + 3
            }
            // Loop line: bold every digit run (counters, not prose).
            if (raw.substring(lineStart, lineEnd).startsWith("Loop:")) {
                var i = lineStart
                while (i < lineEnd) {
                    if (raw[i].isDigit()) {
                        var j = i
                        while (j < lineEnd && (raw[j].isDigit() || raw[j] == '/')) j++
                        ss.setSpan(android.text.style.StyleSpan(Typeface.BOLD),
                            i, j, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = j
                    } else i++
                }
            }
            val line = raw.substring(lineStart, lineEnd)
            val token: Pair<String, Int>? = when {
                line.startsWith("Accessibility service: ON") -> "ON" to p.success
                line.startsWith("Accessibility service: OFF") -> "OFF" to p.danger
                line.startsWith("FAL state: ARMED") -> "ARMED" to p.success
                line.startsWith("FAL state: LATCHED OFF") -> "LATCHED OFF" to p.danger
                // Run 5: the CLS tripwire line gets the same glanceable
                // treatment — ARMED = success, UNAVAILABLE = warning
                // (a degraded tripwire, not a guard failure).
                line.startsWith("CLS state: ARMED") -> "ARMED" to p.success
                line.startsWith("CLS state: UNAVAILABLE") -> "UNAVAILABLE" to p.warning
                else -> null
            }
            if (token != null) {
                val ts = raw.indexOf(token.first, lineStart)
                if (ts in lineStart until lineEnd) {
                    ss.setSpan(android.text.style.ForegroundColorSpan(token.second),
                        ts, ts + token.first.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ss.setSpan(android.text.style.StyleSpan(Typeface.BOLD),
                        ts, ts + token.first.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            lineStart = lineEnd + 1
        }
        return ss
    }

    /** Run 2: small hint line under a control — short explanations live
     *  here, never appended to a label (the overflow defect).
     *  Run 6: hint colour faint -> muted — at 12.5sp the faint token sat
     *  below the 4.5:1 contrast floor in BOTH themes (light faint on
     *  white ~3:1); muted clears it while staying clearly secondary. */
    fun hint(ctx: Context, s: String): TextView =
        text(ctx, s, 12.5f, palette(ctx).muted).apply {
            setPadding(0, 0, 0, dp(ctx, 4))
            setLineSpacing(0f, 1.25f)
        }

    /** Run 2: disabled-aware text colours so a disabled button visibly
     *  dims in both themes (Run 1 carried defect). */
    fun textStates(ctx: Context, enabledColor: Int): ColorStateList {
        val p = palette(ctx)
        return ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(p.faint, enabledColor)
        )
    }

    /** Run 2: accent-tinted slider on the tokens. */
    fun styledSeek(ctx: Context): SeekBar = SeekBar(ctx).apply {
        val p = palette(ctx)
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                thumbTintList = ColorStateList.valueOf(p.accent)
                progressTintList = ColorStateList.valueOf(p.accent)
                progressBackgroundTintList = ColorStateList.valueOf(p.stroke)
            } catch (_: Exception) { /* stock tints — cosmetic only */ }
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    /** Run 2: thin accent progress line (Unlock countdown). */
    fun progressLine(ctx: Context): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            val p = palette(ctx)
            max = 1000
            if (Build.VERSION.SDK_INT >= 21) {
                try {
                    progressTintList = ColorStateList.valueOf(p.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(p.stroke)
                } catch (_: Exception) { /* stock — cosmetic only */ }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            ).apply { topMargin = dp(ctx, 14); bottomMargin = dp(ctx, 6) }
        }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(palette(ctx).stroke)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)
        ).apply { topMargin = dp(ctx, 12); bottomMargin = dp(ctx, 12) }
    }

    private fun baseButton(ctx: Context, label: String): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = dp(ctx, 48)
        setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 10) }
    }

    fun primaryButton(ctx: Context, label: String, action: () -> Unit): Button {
        val p = palette(ctx)
        return baseButton(ctx, label).apply {
            // Run 2 screenshot fix: a disabled primary (the Unlock CTA
            // during the prayer) kept the bright accent fill under dim
            // text — low contrast. Disabled = surface fill instead.
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled),
                    rounded(ctx, p.surface2, 12, p.stroke))
                addState(intArrayOf(), rounded(ctx, p.accent, 12))
            }
            setTextColor(textStates(ctx, p.accentText))
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { action() }
        }
    }

    fun secondaryButton(ctx: Context, label: String, action: () -> Unit): Button {
        val p = palette(ctx)
        return baseButton(ctx, label).apply {
            background = rounded(ctx, p.surface2, 12, p.stroke)
            setTextColor(textStates(ctx, p.text))
            setOnClickListener { action() }
        }
    }

    fun dangerButton(ctx: Context, label: String, action: () -> Unit): Button {
        val p = palette(ctx)
        return baseButton(ctx, label).apply {
            background = rounded(ctx, p.surface2, 12, p.stroke)
            setTextColor(textStates(ctx, p.danger))
            setOnClickListener { action() }
        }
    }

    fun styledSwitch(ctx: Context, label: String): Switch {
        val p = palette(ctx)
        return Switch(ctx).apply {
            text = label
            textSize = 15f
            setTextColor(p.text)
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    // State-aware thumb: an OFF switch must not show the
                    // accent thumb (Run 1 screenshot defect).
                    // Run 4: the light OFF thumb was white on a pale
                    // track — near-invisible in the Run 3 light shots.
                    // Light OFF now uses a slate thumb on a mid-grey
                    // track; dark OFF is unchanged.
                    thumbTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(p.accent, if (isDark(ctx)) Color.rgb(124, 141, 166) else Color.rgb(100, 116, 139))
                    )
                    trackTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(p.accent, if (isDark(ctx)) p.stroke else Color.rgb(203, 213, 225))
                    )
                } catch (_: Exception) { /* stock tints on failure — cosmetic only */ }
            }
        }
    }
}
