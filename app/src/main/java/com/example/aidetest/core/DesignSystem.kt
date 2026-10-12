package com.example.aidetest

import android.graphics.Color

/**
 * Design system global GITLS Publisher.
 * Satu sumber untuk spacing, radius, touch target, tipografi, dan warna status
 * agar semua tool terasa konsisten dan lebih layak.
 */
object Ds {
    /**
     * Dark-theme surface remapper for legacy hard-coded light UI fills.
     * Only near-neutral, near-white colors are remapped; real content colors
     * (photo swatches, status colors, syntax colors, etc.) remain untouched.
     */
    fun darkSurfaceFill(color: Int, dark: Boolean, surface: Int, surfaceAlt: Int): Int {
        if (!dark) return color
        if (color == Color.WHITE) return surface
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val avg = (r + g + b) / 3
        // Covers the app's legacy #F0..#FA grayscale/neutral cards without
        // hijacking intentionally colored UI states.
        return if (max - min <= 5 && avg >= 232) surfaceAlt else color
    }

    fun darkBorder(color: Int, dark: Boolean, line: Int): Int {
        if (!dark) return color
        if (color == Color.WHITE) return color // Used by photo/color selection markers.
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val avg = (r + g + b) / 3
        return if (max - min <= 6 && avg >= 190) line else color
    }

    // Spacing (dp) — 4pt grid, compact but breathable
    const val SPACE_XS = 4
    const val SPACE_SM = 8
    const val SPACE_MD = 12
    const val SPACE_LG = 16
    const val SPACE_XL = 20
    const val SPACE_XXL = 28
    const val SPACE_XXXL = 36

    // Content page padding (horizontal / vertical / bottom clearance for nav)
    const val CONTENT_H = 16
    const val CONTENT_V = 10
    const val CONTENT_BOTTOM = 32
    const val HOME_H = 14
    const val HOME_V = 6
    const val HOME_BOTTOM = 20

    // Radius (dp) — soft modern curves, not oversized
    const val RADIUS_SM = 12
    const val RADIUS_MD = 16
    const val RADIUS_LG = 20
    const val RADIUS_XL = 26
    const val RADIUS_PILL = 999

    // Touch target minimum (dp) — Material 48 + slight comfort
    const val TOUCH_MIN = 48
    const val INPUT_MIN = 52
    const val BUTTON_MIN = 48

    // Animasi (ms) — snappy but calm
    const val ANIM_FAST = 90L
    const val ANIM_NORMAL = 180L
    const val ANIM_SLOW = 280L

    // Tipografi (sp)
    const val TEXT_CAPTION = 11f
    const val TEXT_CAPTION_MIN = 12f
    const val TEXT_BODY = 15f
    const val TEXT_SUBTITLE = 13f
    const val TEXT_TITLE = 17f
    const val TEXT_HERO = 22f
    const val TEXT_DISPLAY = 28f

    // Icon sizes
    const val ICON_SM = 16f
    const val ICON_MD = 20f
    const val ICON_LG = 24f

    enum class State { LOADING, SUCCESS, ERROR, EMPTY, INFO, WARNING }

    fun statusColor(state: State, dark: Boolean): Int = when (state) {
        State.SUCCESS -> if (dark) Color.rgb(74, 201, 120) else Color.rgb(22, 128, 61)
        State.WARNING -> if (dark) Color.rgb(245, 179, 66) else Color.rgb(180, 83, 9)
        State.ERROR -> if (dark) Color.rgb(255, 107, 107) else Color.rgb(185, 28, 28)
        State.INFO, State.LOADING -> if (dark) Color.rgb(96, 165, 250) else Color.rgb(29, 78, 216)
        State.EMPTY -> if (dark) Color.rgb(155, 155, 160) else Color.rgb(100, 100, 108)
    }

    fun statusSoftBg(state: State, dark: Boolean): Int = when (state) {
        State.SUCCESS -> if (dark) Color.rgb(20, 48, 32) else Color.rgb(220, 252, 231)
        State.WARNING -> if (dark) Color.rgb(48, 36, 12) else Color.rgb(254, 243, 199)
        State.ERROR -> if (dark) Color.rgb(48, 20, 20) else Color.rgb(254, 226, 226)
        State.INFO, State.LOADING -> if (dark) Color.rgb(20, 32, 56) else Color.rgb(219, 234, 254)
        State.EMPTY -> if (dark) Color.rgb(36, 36, 40) else Color.rgb(241, 245, 249)
    }

    fun stateIcon(state: State): String = when (state) {
        State.SUCCESS -> "check-circle-outline"
        State.ERROR -> "alert-circle-outline"
        State.WARNING -> "alert-outline"
        State.INFO -> "information-outline"
        State.LOADING -> "timer-sand"
        State.EMPTY -> "inbox-outline"
    }

    fun moneyIn(dark: Boolean): Int =
        if (dark) Color.rgb(74, 201, 120) else Color.rgb(22, 128, 61)

    fun moneyOut(dark: Boolean): Int =
        if (dark) Color.rgb(255, 120, 120) else Color.rgb(185, 28, 28)

    fun moneyNeutral(dark: Boolean): Int =
        if (dark) Color.rgb(180, 180, 186) else Color.rgb(90, 90, 98)
}
