package com.rem.screenguard

import android.content.Context
import android.graphics.Color

/**
 * v0.19.0 (play-iap): appearance themes for the SgUi token system.
 *
 * A theme is an accent token pair (accent + the text colour that
 * stays readable on it) plus, since v0.19.11's design overhaul, a
 * RICH palette (secondary accent + deep gradient tint) laid over
 * the ink surfaces. Surface, text, stroke and the semantic colours
 * (success / warning / danger) are identical under every theme;
 * the data-series colours on the score graph are likewise
 * untouched. The overhaul's gradients and chart series read the
 * rich palette, so a theme now re-skins the whole app — and the
 * paid themes' richer gradients are the visible payload.
 *
 * "Sky" — the shipping look — is free forever. The other five are the
 * Supporter Pack's entire payload: a one-time purchase unlocks the
 * right to SELECT them. Nothing about protection reads this file,
 * and the unlock ritual is not themed and never sold.
 *
 * The block / quarantine covers are NOT themed: GuardService paints
 * them from its own fixed colour literals and never touches SgUi or
 * this file, so a cover keeps full contrast under every theme by
 * construction.
 */
object SgTheme {

    data class Accents(val accent: Int, val accentText: Int)

    /**
     * v0.19.11 (design overhaul, theme-first): the RICH palette —
     * a secondary accent (gradients, chart series B, today's-bar
     * distinction) and a deep tint (the colour card gradients sink
     * toward). Paid themes pair their accent with a contrasting
     * accent2 and a saturated deep, so their gradients read as
     * visibly richer than Sky's subtle two-stop ink-blue.
     */
    data class Rich(val accent2: Int, val deep: Int)

    data class Theme(
        val key: String,
        val name: String,
        val supporter: Boolean,
        val dark: Accents,
        val light: Accents,
        val darkRich: Rich,
        val lightRich: Rich
    )

    const val DEFAULT_KEY = "sky"

    val THEMES: List<Theme> = listOf(
        Theme(
            "sky", "Sky", supporter = false,
            dark = Accents(Color.rgb(56, 189, 248), Color.rgb(8, 24, 40)),
            light = Accents(Color.rgb(2, 132, 199), Color.WHITE),
            darkRich = Rich(Color.rgb(167, 139, 250), Color.rgb(16, 42, 74)),
            lightRich = Rich(Color.rgb(124, 58, 237), Color.rgb(224, 242, 254))
        ),
        Theme(
            "sunset", "Sunset", supporter = true,
            dark = Accents(Color.rgb(251, 146, 60), Color.rgb(43, 18, 4)),
            light = Accents(Color.rgb(234, 88, 12), Color.WHITE),
            darkRich = Rich(Color.rgb(250, 204, 21), Color.rgb(58, 26, 10)),
            lightRich = Rich(Color.rgb(202, 138, 4), Color.rgb(255, 237, 213))
        ),
        Theme(
            "forest", "Forest", supporter = true,
            dark = Accents(Color.rgb(74, 222, 128), Color.rgb(5, 32, 16)),
            light = Accents(Color.rgb(22, 163, 74), Color.WHITE),
            darkRich = Rich(Color.rgb(45, 212, 191), Color.rgb(10, 44, 26)),
            lightRich = Rich(Color.rgb(13, 148, 136), Color.rgb(220, 252, 231))
        ),
        Theme(
            "violet", "Violet", supporter = true,
            dark = Accents(Color.rgb(167, 139, 250), Color.rgb(26, 10, 54)),
            light = Accents(Color.rgb(124, 58, 237), Color.WHITE),
            darkRich = Rich(Color.rgb(244, 114, 182), Color.rgb(38, 20, 66)),
            lightRich = Rich(Color.rgb(219, 39, 119), Color.rgb(237, 233, 254))
        ),
        Theme(
            "rose", "Rose", supporter = true,
            dark = Accents(Color.rgb(251, 113, 133), Color.rgb(46, 5, 16)),
            light = Accents(Color.rgb(225, 29, 72), Color.WHITE),
            darkRich = Rich(Color.rgb(251, 146, 60), Color.rgb(54, 14, 24)),
            lightRich = Rich(Color.rgb(234, 88, 12), Color.rgb(255, 228, 230))
        ),
        Theme(
            "ocean", "Ocean", supporter = true,
            dark = Accents(Color.rgb(45, 212, 191), Color.rgb(4, 32, 28)),
            light = Accents(Color.rgb(13, 148, 136), Color.WHITE),
            darkRich = Rich(Color.rgb(56, 189, 248), Color.rgb(8, 40, 50)),
            lightRich = Rich(Color.rgb(2, 132, 199), Color.rgb(204, 251, 241))
        )
    )

    fun byKey(key: String): Theme = THEMES.firstOrNull { it.key == key } ?: THEMES[0]

    /** The user's stored choice (Prefs), resolved to a Theme. */
    fun current(ctx: Context): Theme = byKey(Prefs.themeKey(ctx))

    fun accents(key: String, dark: Boolean): Accents {
        val t = byKey(key)
        return if (dark) t.dark else t.light
    }

    /** v0.19.11: the rich palette (accent2 + deep) for a theme/mode. */
    fun rich(key: String, dark: Boolean): Rich {
        val t = byKey(key)
        return if (dark) t.darkRich else t.lightRich
    }

    /** Swatch colour for lists: the theme's accent in the current mode. */
    fun swatch(ctx: Context, theme: Theme): Int =
        if (SgUi.isDark(ctx)) theme.dark.accent else theme.light.accent
}
