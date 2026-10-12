package com.example.aidetest

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * Palet monokrom + drawable bersama untuk tampilan bergaya editor profesional
 * (Workspace Beranda, Laci Tools, Laci Editor).
 *
 * Pedoman tata letak editor layar penuh (Studio Kreatif, Video Editor, Audio Editor, Auto Trace):
 *  - Top bar 52 dp: kembali (arrow-left 23, sel 44×44) · judul 15 tebal + subjudul 11 · aksi ikon
 *    (sel 40×44) · menu ⋮ · tombol utama terisi (tinggi 36, radius 18).
 *  - Dock bawah: sel 66×54, ikon 21 di atas label 10, aktif = terisi [activeFill]; garis 1 px di atasnya.
 *  - Panel: kepala 42 dp (judul 14 tebal + chevron-down 44×40), isi berpadding 12.
 *  - Kontrol: slider satu baris (label 84 · bar · nilai 52, ketuk nilai = reset), sakelar 48×28,
 *    pil radius 16, tombol radius 18, ubin radius 14; sasaran sentuh ≥ 40–44 dp.
 *  - Area kerja konten (kanvas/foto ikut tema; pratinjau & timeline video selalu gelap).
 *
 * Sengaja TIDAK memakai MainActivity.bg(): helper itu me-remap warna near-white
 * saat tema gelap, sehingga chip aktif berwarna putih di tema gelap akan ikut
 * berubah menjadi abu-abu dan tidak terlihat aktif.
 */
internal class EditorChrome(private val a: MainActivity) {
    val dark: Boolean = a.isDarkTheme
    val surface: Int = if (dark) Color.rgb(24, 24, 26) else Color.WHITE
    val surface2: Int = if (dark) Color.rgb(34, 34, 37) else Color.rgb(242, 242, 244)
    val stroke: Int = if (dark) Color.rgb(54, 54, 58) else Color.rgb(226, 226, 230)
    val ink: Int = if (dark) Color.rgb(245, 245, 247) else Color.rgb(18, 18, 20)
    val muted: Int = if (dark) Color.rgb(150, 150, 156) else Color.rgb(110, 110, 118)
    val activeFill: Int = ink
    val activeInk: Int = if (dark) Color.rgb(18, 18, 20) else Color.WHITE
    val scrim: Int = if (dark) Color.argb(150, 0, 0, 0) else Color.argb(96, 0, 0, 0)

    fun dp(v: Int): Int = a.dp(v)

    fun shape(fill: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(strokeDp), strokeColor)
        return d
    }

    /** Background dengan ripple ringan (tanpa blur / shadow berat). */
    fun pressable(fill: Int, radiusDp: Int, strokeColor: Int? = null): Drawable {
        val rippleColor = if (dark) Color.argb(48, 255, 255, 255) else Color.argb(30, 0, 0, 0)
        return RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            shape(fill, radiusDp, strokeColor),
            shape(Color.BLACK, radiusDp)
        )
    }
}

/**
 * Jembatan tombol Back untuk panel overlay (laci Workspace, laci Editor).
 * Panel yang terbuka mendaftarkan handler; Back menutup panel teratas lebih dulu
 * sebelum navigasi halaman biasa berjalan.
 */
internal object OverlayBack {
    private val handlers = ArrayList<() -> Boolean>()

    fun push(handler: () -> Boolean) {
        handlers.remove(handler)
        handlers.add(handler)
    }

    fun remove(handler: () -> Boolean) {
        handlers.remove(handler)
    }

    fun hasOpen(): Boolean = handlers.isNotEmpty()

    /** @return true bila ada panel yang benar-benar ditutup. */
    fun consume(): Boolean {
        while (handlers.isNotEmpty()) {
            val handler = handlers.removeAt(handlers.size - 1)
            if (handler()) return true
        }
        return false
    }
}
