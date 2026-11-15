package com.rem.screenguard

import android.content.Context
import android.graphics.Color

/**
 * v0.19.0 (play-iap): appearance themes for the SgUi token system.
 *
 * A theme is ONLY an accent token pair (accent + the text colour that
 * stays readable on it) laid over the unchanged ink surfaces. Surface,
 * text, stroke and the semantic colours (success / warning / danger)
 * are identical under every theme; the data-series colours on the
 * score graph are likewise untouched.
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

    data class Theme(
        val key: String,
        val name: String,
        val supporter: Boolean,
        val dark: Accents,
        val light: Accents
    )

    const val DEFAULT_KEY = "sky"

    val THEMES: List<Theme> = listOf(
        Theme(
            "sky", "Sky", supporter = false,
            dark = Accents(Color.rgb(56, 189, 248), Color.rgb(8, 24, 40)),
            light = Accents(Color.rgb(2, 132, 199), Color.WHITE)
        ),
        Theme(
            "sunset", "Sunset", supporter = true,
            dark = Accents(Color.rgb(251, 146, 60), Color.rgb(43, 18, 4)),
            light = Accents(Color.rgb(234, 88, 12), Color.WHITE)
        ),
        Theme(
            "forest", "Forest", supporter = true,
            dark = Accents(Color.rgb(74, 222, 128), Color.rgb(5, 32, 16)),
            light = Accents(Color.rgb(22, 163, 74), Color.WHITE)
        ),
        Theme(
            "violet", "Violet", supporter = true,
            dark = Accents(Color.rgb(167, 139, 250), Color.rgb(26, 10, 54)),
            light = Accents(Color.rgb(124, 58, 237), Color.WHITE)
        ),
        Theme(
            "rose", "Rose", supporter = true,
            dark = Accents(Color.rgb(251, 113, 133), Color.rgb(46, 5, 16)),
            light = Accents(Color.rgb(225, 29, 72), Color.WHITE)
        ),
        Theme(
            "ocean", "Ocean", supporter = true,
            dark = Accents(Color.rgb(45, 212, 191), Color.rgb(4, 32, 28)),
            light = Accents(Color.rgb(13, 148, 136), Color.WHITE)
        )
    )

    fun byKey(key: String): Theme = THEMES.firstOrNull { it.key == key } ?: THEMES[0]

    /** The user's stored choice (Prefs), resolved to a Theme. */
    fun current(ctx: Context): Theme = byKey(Prefs.themeKey(ctx))

    fun accents(key: String, dark: Boolean): Accents {
        val t = byKey(key)
        return if (dark) t.dark else t.light
    }

    /** Swatch colour for lists: the theme's accent in the current mode. */
    fun swatch(ctx: Context, theme: Theme): Int =
        if (SgUi.isDark(ctx)) theme.dark.accent else theme.light.accent
}
