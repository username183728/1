package com.example.aidetest

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Proyeksi miring 3D untuk layer Foto Studio. Murni matematika (tanpa API Android) agar bisa diuji di JVM.
 *
 * Bidang layer (lebar w × tinggi h, pusat di titik asal) diputar [CsElement.tiltX] derajat pada sumbu X
 * dan [CsElement.tiltY] derajat pada sumbu Y, lalu diproyeksikan oleh kamera dengan jarak fokus [focal].
 * Hasilnya tetap satu bidang gambar (bukan model 3D sungguhan), tetapi sisi yang lebih dekat terlihat lebih besar.
 */
internal object Pe3D {
    const val MAX_TILT = 60f

    /** Batas sisi terpanjang bitmap sementara saat layer miring digambar (hemat memori). */
    const val MAX_SIDE = 2048f

    /** Jarak kamera relatif sisi terpanjang layer. Nilai besar = proyeksi mendekati ortografik (tidak terlalu ekstrem). */
    private const val FOCAL = 2.2

    fun tilted(el: CsElement): Boolean = el.tiltX != 0f || el.tiltY != 0f

    /**
     * Titik lokal (px, py) pada bidang layer (pusat di 0,0) → koordinat layar relatif pusat layer.
     */
    fun project(px: Float, py: Float, w: Float, h: Float, tiltX: Float, tiltY: Float): FloatArray {
        val ax = Math.toRadians(tiltX.coerceIn(-MAX_TILT, MAX_TILT).toDouble())
        val ay = Math.toRadians(tiltY.coerceIn(-MAX_TILT, MAX_TILT).toDouble())
        val f = FOCAL * max(w, h).toDouble()
        // Putar pada sumbu X, lalu sumbu Y.
        val y1 = py * cos(ax)
        val z1 = py * sin(ax)
        val x2 = px * cos(ay) + z1 * sin(ay)
        val z2 = -px * sin(ay) + z1 * cos(ay)
        // z2 positif = menjauh dari kamera. Sisi yang menjauh tampak lebih kecil.
        val s = f / (f - z2)
        return floatArrayOf((x2 * s).toFloat(), (y1 * s).toFloat())
    }

    /** Empat sudut layer (kiri-atas, kanan-atas, kanan-bawah, kiri-bawah) relatif pusat. */
    fun corners(w: Float, h: Float, tiltX: Float, tiltY: Float): FloatArray {
        val hw = w / 2f
        val hh = h / 2f
        val out = FloatArray(8)
        val pts = arrayOf(floatArrayOf(-hw, -hh), floatArrayOf(hw, -hh), floatArrayOf(hw, hh), floatArrayOf(-hw, hh))
        for (i in 0 until 4) {
            val p = project(pts[i][0], pts[i][1], w, h, tiltX, tiltY)
            out[i * 2] = p[0]
            out[i * 2 + 1] = p[1]
        }
        return out
    }

    /**
     * Titik kisi (n+1)×(n+1) untuk [android.graphics.Canvas.drawBitmapMesh], relatif pusat layer.
     * Setiap titik diproyeksikan sendiri, jadi perspektifnya halus walau gambar dibagi kotak-kotak.
     */
    fun mesh(w: Float, h: Float, tiltX: Float, tiltY: Float, n: Int): FloatArray {
        val cells = max(1, n)
        val out = FloatArray((cells + 1) * (cells + 1) * 2)
        var k = 0
        for (j in 0..cells) for (i in 0..cells) {
            val px = (i.toFloat() / cells - 0.5f) * w
            val py = (j.toFloat() / cells - 0.5f) * h
            val p = project(px, py, w, h, tiltX, tiltY)
            out[k++] = p[0]
            out[k++] = p[1]
        }
        return out
    }

    /** Ukuran bitmap sementara: sisi terpanjang dibatasi [MAX_SIDE]. Mengembalikan faktor skala (≤ 1). */
    fun scaleFor(w: Float, h: Float): Float = min(1f, MAX_SIDE / max(max(w, h), 1f))
}
