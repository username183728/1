package com.example.aidetest

import java.util.UUID

/*
 * Model murni Foto Studio (gabungan Photo Editor + Design Studio + Hapus Latar).
 * Tidak memakai API Android sehingga codec & logika bisa diuji di JVM (CsCodecTest).
 *
 * Satu dokumen = kanvas + daftar layer berurutan (indeks 0 = paling belakang).
 * Layer foto membawa:
 *  - salinan sumber privat ([CsElement.imagePath], ukuran [srcW]×[srcH] setelah orientasi EXIF)
 *  - edit foto non-destruktif ([PeEdit] dari PhotoEngine: cahaya, warna, kurva, HSL, filter,
 *    detail, crop/putar/flip/luruskan/perspektif, heal) — immutable, aman dibagi antar snapshot undo
 *  - mask subjek di ruang sumber, disimpan terpisah dan dirujuk lewat [CsElement.maskId]
 *    (setiap perubahan mask = id baru, jadi undo cukup menyimpan id)
 *  - parameter potong latar [CsCut]
 */

/** Jenis layer. Nama enum = nilai "type" di JSON (kompatibel proyek Design Studio v2). */
internal enum class CsType { TEXT, RECT, ELLIPSE, IMAGE, LINE, TRIANGLE, STICKER }

/** Perataan teks. Nama sama dengan Paint.Align agar proyek v2 terbaca apa adanya. */
internal enum class CsAlign { LEFT, CENTER, RIGHT }

/** Parameter potong latar layer foto. Mask mentahnya dirujuk oleh [CsElement.maskId]. */
internal data class CsCut(
    /** true = latar dihapus (mask diterapkan sebagai transparansi). */
    val on: Boolean = false,
    /** Metode terakhir: subjek | orang | warna | kuas. */
    val method: String = "",
    /** Halus tepi 0..100. */
    val smooth: Int = 30,
    /** Geser tepi -12..12 px (ruang kerja). */
    val shift: Int = 0,
    /** Feather 0..24 px (ruang kerja). */
    val feather: Int = 1,
    /** Dekontaminasi warna tepi (hapus halo latar lama). */
    val decontam: Boolean = true,
    /** Toleransi mode warna latar 5..120. */
    val tolerance: Int = 34
)

internal data class CsElement(
    val id: String = UUID.randomUUID().toString(),
    var type: CsType,
    var x: Float,
    var y: Float,
    var w: Float,
    var h: Float,
    var rotation: Float = 0f,
    var opacity: Float = 1f,
    /** Mode campur: normal | multiply | screen | overlay | darken | lighten | add. */
    var blend: String = "normal",
    var visible: Boolean = true,
    var locked: Boolean = false,
    var groupId: String? = null,
    /** Nama layer kustom (kosong = nama otomatis). */
    var name: String = "",
    // ---- isi & garis (bentuk, stiker, teks)
    var color: Int = -1,
    /** Isi gradien linear [color] → [color2] dengan sudut [gradAngle] (bentuk). */
    var gradient: Boolean = false,
    var color2: Int = 0xFF808080.toInt(),
    var gradAngle: Float = 90f,
    var strokeColor: Int = 0,
    var strokeWidth: Float = 0f,
    var cornerRadius: Float = 0f,
    // ---- bayangan (offset dalam px kanvas)
    var shadow: Boolean = false,
    var shadowBlur: Float = 12f,
    var shadowColor: Int = 0x96000000.toInt(),
    var shadowDx: Float = 4f,
    var shadowDy: Float = 4f,
    // ---- teks
    var text: String = "",
    var textSize: Float = 48f,
    var font: String = "sans",
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var align: CsAlign = CsAlign.LEFT,
    var letterSpacing: Float = 0f,
    var lineSpacing: Float = 1.2f,
    /** Kotak latar di belakang teks (alpha 0 = mati). */
    var textBg: Int = 0,
    /** Padding kotak latar teks, relatif ukuran huruf. */
    var textBgPad: Float = 0.3f,
    // ---- stiker: ikon MDI, atau emoji di [text] bila kosong
    var icon: String = "",
    // ---- foto
    var imagePath: String? = null,
    var srcW: Int = 0,
    var srcH: Int = 0,
    var edit: PeEdit = PeEdit(),
    var maskId: String? = null,
    var cut: CsCut = CsCut(),
    /** Foto utama dokumen: selalu menutupi kanvas; crop/putar mengubah ukuran kanvas. */
    var fitCanvas: Boolean = false,
    /** "latar:<idFoto>" = layer latar pengganti yang dibuat panel Hapus Latar. */
    var role: String = ""
)

/** Latar kanvas: solid | gradient | transparent. */
internal data class CsBackground(
    val mode: String = "solid",
    val color: Int = -1,
    val color2: Int = 0xFF000000.toInt(),
    val angle: Float = 90f
)

/** Isi dokumen untuk disimpan/dimuat (tanpa state UI seperti seleksi dan zoom). */
internal class CsDocData(
    val name: String,
    val w: Int,
    val h: Int,
    val bg: CsBackground,
    val elements: List<CsElement>
)
