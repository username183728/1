package com.example.aidetest

import android.graphics.Color

/**
 * Design system global GITLS Publisher.
 * Satu sumber untuk spacing, radius, touch target, durasi animasi, dan warna status
 * (success / warning / error / info) yang dipakai semua tool agar konsisten.
 */
object Ds {
    // Spacing (dp)
    const val SPACE_XS = 4
    const val SPACE_SM = 8
    const val SPACE_MD = 12
    const val SPACE_LG = 16
    const val SPACE_XL = 20
    const val SPACE_XXL = 24

    // Radius (dp)
    const val RADIUS_SM = 10
    const val RADIUS_MD = 14
    const val RADIUS_LG = 20

    // Touch target minimum (dp)
    const val TOUCH_MIN = 48

    // Animasi ringan (ms)
    const val ANIM_FAST = 120L
    const val ANIM_NORMAL = 200L

    // Ukuran teks minimum agar subtitle tetap terbaca (sp)
    const val TEXT_CAPTION_MIN = 12f

    enum class State { LOADING, SUCCESS, ERROR, EMPTY, INFO, WARNING }

    /** Warna status; nilai dibedakan untuk Light/Dark agar kontras tetap cukup. */
    fun statusColor(state: State, dark: Boolean): Int = when (state) {
        State.SUCCESS -> if (dark) Color.rgb(74, 201, 120) else Color.rgb(22, 128, 61)
        State.WARNING -> if (dark) Color.rgb(245, 179, 66) else Color.rgb(180, 83, 9)
        State.ERROR -> if (dark) Color.rgb(255, 107, 107) else Color.rgb(185, 28, 28)
        State.INFO, State.LOADING -> if (dark) Color.rgb(96, 165, 250) else Color.rgb(29, 78, 216)
        State.EMPTY -> if (dark) Color.rgb(155, 155, 160) else Color.rgb(100, 100, 108)
    }

    /** Ikon MDI untuk tiap state (semua nama sudah ada di MdiGlyphs jika tersedia). */
    fun stateIcon(state: State): String = when (state) {
        State.SUCCESS -> "check-circle-outline"
        State.ERROR -> "alert-circle-outline"
        State.WARNING -> "alert-outline"
        State.INFO -> "information-outline"
        State.LOADING -> "timer-sand"
        State.EMPTY -> "folder-outline"
    }
}
