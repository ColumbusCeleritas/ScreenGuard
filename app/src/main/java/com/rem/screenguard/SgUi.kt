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
 * PRESENTATION ONLY — no behaviour lives here; the palette's one
 * input is the stored theme choice (v0.19.0, see SgTheme).
 * Dark-first: deep ink-navy surfaces, one accent (themed), rounded
 * cards. The light palette uses the same tokens on light surfaces.
 */
object SgUi {

    data class Palette(
        val bg: Int, val surface: Int, val surface2: Int, val stroke: Int,
        val text: Int, val muted: Int, val faint: Int,
        val accent: Int, val accentText: Int,
        val success: Int, val warning: Int, val danger: Int,
        // v0.19.11 (design overhaul): the theme's rich palette —
        // accent2 (gradients / chart series B), deep (the tint card
        // gradients sink toward) and rich = the active theme is a
        // paid theme (its gradients carry the extra accent stop).
        val accent2: Int, val deep: Int, val rich: Boolean
    )

    // v0.19.11 TYPE SCALE (paradigm 1): the only text steps the
    // screens may use. DISPLAY = the app name on Home; TITLE =
    // screen titles; HEADLINE = section headers and row titles;
    // BODY / SMALL = copy; CAPTION = hints and subtitles.
    const val SP_DISPLAY = 30f
    const val SP_TITLE = 24f
    const val SP_HEADLINE = 16f
    const val SP_BODY = 14.5f
    const val SP_SMALL = 13.5f
    const val SP_CAPTION = 12.5f

    // v0.19.11 RADIUS SCALE (paradigm 5): one scale everywhere —
    // cards 20, inner controls 14, small chips 10, pills round.
    const val RAD_CARD = 20
    const val RAD_INNER = 14
    const val RAD_SMALL = 10

    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun palette(ctx: Context): Palette {
        val dark = isDark(ctx)
        // v0.19.0 (play-iap): the accent pair comes from the chosen
        // appearance theme (SgTheme; "Sky" = the original shipping
        // accents, free forever). Surfaces, text, strokes and the
        // semantic colours are theme-invariant. GuardService never
        // calls this — the covers are painted from fixed literals
        // there and keep full contrast under every theme.
        val acc = SgTheme.accents(Prefs.themeKey(ctx), dark)
        val rich = SgTheme.rich(Prefs.themeKey(ctx), dark)
        val supporter = SgTheme.byKey(Prefs.themeKey(ctx)).supporter
        return if (dark) Palette(
            bg = Color.rgb(11, 18, 32),          // ink navy
            surface = Color.rgb(22, 32, 51),
            surface2 = Color.rgb(30, 42, 64),
            stroke = Color.rgb(46, 61, 86),
            text = Color.rgb(230, 237, 247),
            muted = Color.rgb(154, 168, 191),
            faint = Color.rgb(110, 124, 148),
            accent = acc.accent,
            accentText = acc.accentText,
            success = Color.rgb(52, 211, 153),
            warning = Color.rgb(251, 191, 36),
            danger = Color.rgb(248, 113, 113),
            accent2 = rich.accent2,
            deep = rich.deep,
            rich = supporter
        ) else Palette(
            bg = Color.rgb(242, 245, 249),
            surface = Color.rgb(255, 255, 255),
            surface2 = Color.rgb(233, 238, 245),
            stroke = Color.rgb(211, 219, 231),
            text = Color.rgb(15, 27, 46),
            muted = Color.rgb(91, 107, 130),
            faint = Color.rgb(133, 148, 168),
            accent = acc.accent,
            accentText = acc.accentText,
            success = Color.rgb(5, 150, 105),
            warning = Color.rgb(180, 120, 10),
            danger = Color.rgb(200, 40, 40),
            accent2 = rich.accent2,
            deep = rich.deep,
            rich = supporter
        )
    }

    /** Blend [from] toward [to] by [f] (0 = from, 1 = to). */
    fun blend(from: Int, to: Int, f: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.rgb(r, g, b)
    }

    /** [color] with its alpha replaced (0–255). */
    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /**
     * v0.19.11 (paradigm 5): the card gradient for the active
     * theme — surface sinking toward the theme's deep tint; paid
     * themes carry a third, accent-tinted stop (the visible
     * richness the Supporter Pack sells). Sky stays a subtle
     * two-stop ink gradient.
     */
    fun cardGradient(ctx: Context): GradientDrawable {
        val p = palette(ctx)
        val colors = if (p.rich) intArrayOf(
            p.surface,
            blend(p.surface, p.deep, 0.55f),
            blend(p.surface, p.accent, 0.16f)
        ) else intArrayOf(
            p.surface,
            blend(p.surface, p.deep, 0.65f)
        )
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(ctx, RAD_CARD).toFloat()
            setStroke(dp(ctx, 1), p.stroke)
        }
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
        }

    /** A vertical card the caller fills; add it to a vertical parent.
     *  v0.19.11 (paradigm 5): layered surface — theme-tinted
     *  gradient fill, real elevation shadow with a rounded outline,
     *  the one RAD_CARD radius. */
    fun card(ctx: Context, topMarginDp: Int = 14): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardGradient(ctx)
            elevation = dp(ctx, 3).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height,
                        dp(ctx, RAD_CARD).toFloat())
                }
            }
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

    /**
     * Section header. v0.19.11 (paradigms 1 + 8): the orange
     * small-caps style RETIRES — headers are sentence-case, text-
     * coloured, HEADLINE step, no letter spacing. The accent no
     * longer colours header text anywhere.
     */
    fun cardTitle(ctx: Context, s: String): TextView =
        text(ctx, s, SP_HEADLINE, palette(ctx).text, bold = true).apply {
            setPadding(0, 0, 0, dp(ctx, 10))
        }

    /** v0.19.11: screen title (TITLE step) — every screen's name. */
    fun screenTitle(ctx: Context, s: String): TextView =
        text(ctx, s, SP_TITLE, palette(ctx).text, bold = true)

    /** v0.19.11: display title (DISPLAY step) — the app name on Home. */
    fun displayTitle(ctx: Context, s: String): TextView =
        text(ctx, s, SP_DISPLAY, palette(ctx).text, bold = true)

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

    /** v0.19.11 (paradigm 4): a pressed-state variant of a fill —
     *  dark themes lighten toward white, light themes darken. */
    private fun pressedFill(ctx: Context, color: Int): Int =
        if (isDark(ctx)) blend(color, Color.WHITE, 0.14f)
        else blend(color, Color.BLACK, 0.10f)

    fun primaryButton(ctx: Context, label: String, action: () -> Unit): Button {
        val p = palette(ctx)
        return baseButton(ctx, label).apply {
            // Run 2 screenshot fix: a disabled primary (the Unlock CTA
            // during the prayer) kept the bright accent fill under dim
            // text — low contrast. Disabled = surface fill instead.
            // v0.19.11: pressed state added (paradigm 4).
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled),
                    rounded(ctx, p.surface2, RAD_INNER, p.stroke))
                addState(intArrayOf(android.R.attr.state_pressed),
                    rounded(ctx, pressedFill(ctx, p.accent), RAD_INNER))
                addState(intArrayOf(), rounded(ctx, p.accent, RAD_INNER))
            }
            setTextColor(textStates(ctx, p.accentText))
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { action() }
        }
    }

    fun secondaryButton(ctx: Context, label: String, action: () -> Unit): Button {
        val p = palette(ctx)
        return baseButton(ctx, label).apply {
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    rounded(ctx, pressedFill(ctx, p.surface2), RAD_INNER, p.stroke))
                addState(intArrayOf(), rounded(ctx, p.surface2, RAD_INNER, p.stroke))
            }
            setTextColor(textStates(ctx, p.text))
            setOnClickListener { action() }
        }
    }

    fun dangerButton(ctx: Context, label: String, action: () -> Unit): Button {
        val p = palette(ctx)
        return baseButton(ctx, label).apply {
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    rounded(ctx, pressedFill(ctx, p.surface2), RAD_INNER, p.stroke))
                addState(intArrayOf(), rounded(ctx, p.surface2, RAD_INNER, p.stroke))
            }
            setTextColor(textStates(ctx, p.danger))
            setOnClickListener { action() }
        }
    }

    /**
     * v0.19.11 (paradigms 4 + 6): restrained motion — a 200 ms
     * fade for every screen transition. Call first in onCreate;
     * cosmetic only, wrapped so a transition failure can never
     * break a screen.
     */
    fun applyMotion(activity: android.app.Activity) {
        try {
            activity.window.requestFeature(android.view.Window.FEATURE_CONTENT_TRANSITIONS)
            val fade = android.transition.Fade().apply { duration = 200 }
            activity.window.enterTransition = fade
            activity.window.exitTransition = fade
        } catch (_: Exception) { /* stock transitions — cosmetic only */ }
    }

    /**
     * v0.19.11 (paradigm 6): a menu row — drawn icon in an
     * accent-tinted tile, HEADLINE title, one-line CAPTION
     * subtitle, chevron; the whole row is the tap target with a
     * pressed state. Replaces the stacked button + hint pairs.
     */
    fun menuRow(
        ctx: Context, icon: SgIcon, title: String, subtitle: String,
        action: () -> Unit
    ): LinearLayout {
        val p = palette(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56)
            setPadding(dp(ctx, 6), dp(ctx, 8), dp(ctx, 2), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    rounded(ctx, pressedFill(ctx, p.surface2), RAD_INNER))
                addState(intArrayOf(), rounded(ctx, Color.TRANSPARENT, RAD_INNER))
            }
            isClickable = true
            setOnClickListener { action() }
        }
        val tile = LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER
            background = rounded(ctx, withAlpha(p.accent, 36), RAD_SMALL)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply {
                rightMargin = dp(ctx, 12)
            }
        }
        tile.addView(SgIcons.view(ctx, icon, p.accent, 22))
        row.addView(tile)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        texts.addView(text(ctx, title, SP_HEADLINE, p.text, bold = true))
        texts.addView(text(ctx, subtitle, SP_CAPTION, p.muted).apply {
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(texts)
        row.addView(text(ctx, "›", 22f, p.faint).apply {
            setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
        })
        return row
    }

    /** v0.19.11 (paradigm 2): a pill chip — icon + label, for the
     *  app-group rows on How Castitas works. */
    fun chip(ctx: Context, icon: SgIcon, label: String): LinearLayout {
        val p = palette(ctx)
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = rounded(ctx, p.surface2, 999, p.stroke)
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 6); rightMargin = dp(ctx, 6) }
        }
        c.addView(SgIcons.view(ctx, icon, p.accent, 16))
        c.addView(text(ctx, label, SP_SMALL, p.text).apply {
            setPadding(dp(ctx, 6), 0, 0, 0)
        })
        return c
    }

    /**
     * v0.19.11 (paradigms 4 + 9): a progressive-disclosure section.
     * The header (title + chevron) is always visible; [content]
     * starts collapsed and fades open/closed in 200 ms on tap.
     * The disclosure's open state lives in the views themselves,
     * so a screen's refresh cycle never re-collapses it.
     */
    fun disclosure(
        ctx: Context, title: String,
        build: (LinearLayout) -> Unit
    ): LinearLayout {
        val p = palette(ctx)
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
        }
        val chevron = text(ctx, "▸", 15f, p.accent, bold = true)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            isClickable = true
        }
        header.addView(text(ctx, title, 15f, p.text, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        header.addView(chevron)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        build(content)
        header.setOnClickListener {
            if (content.visibility == View.VISIBLE) {
                content.animate().alpha(0f).setDuration(180).withEndAction {
                    content.visibility = View.GONE
                    content.alpha = 1f
                }.start()
                chevron.text = "▸"
            } else {
                content.alpha = 0f
                content.visibility = View.VISIBLE
                content.animate().alpha(1f).setDuration(200).start()
                chevron.text = "▾"
            }
        }
        wrap.addView(header)
        wrap.addView(content)
        return wrap
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
