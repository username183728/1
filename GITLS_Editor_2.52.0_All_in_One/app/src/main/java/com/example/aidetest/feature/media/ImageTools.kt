package com.example.aidetest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

// ---------------------------------------------------------------------------
// Shared image pick / load helpers (request codes 1220–1224)
// ---------------------------------------------------------------------------

@Volatile
internal var imageToolPickPending: ((Uri?) -> Unit)? = null

internal fun MainActivity.pickImageForTool(requestCode: Int, onPicked: (Uri?) -> Unit) {
    imageToolPickPending = onPicked
    @Suppress("DEPRECATION")
    startActivityForResult(
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "image/*"
        },
        requestCode
    )
}

/** Ukuran asli gambar (tanpa dekode piksel); orientasi EXIF 90/270 menukar lebar & tinggi. */
internal fun MainActivity.imageOriginalSize(uri: Uri): Pair<Int, Int> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
    val o = VeAssets.exifOrientation(this, uri)
    val swap = o == android.media.ExifInterface.ORIENTATION_ROTATE_90 || o == android.media.ExifInterface.ORIENTATION_ROTATE_270 ||
        o == android.media.ExifInterface.ORIENTATION_TRANSPOSE || o == android.media.ExifInterface.ORIENTATION_TRANSVERSE
    return if (swap) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
}

/** "Asli: W×H px", plus ukuran proses bila gambar diperkecil untuk menghemat memori. */
internal fun sizeNote(orig: Pair<Int, Int>, bmp: Bitmap): String =
    if (orig.first > 0 && (orig.first != bmp.width || orig.second != bmp.height)) "${orig.first}×${orig.second} px (diproses ${bmp.width}×${bmp.height})"
    else "${bmp.width}×${bmp.height} px"

internal fun MainActivity.decodeBitmapFromUri(uri: Uri, maxSide: Int = 2048): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    val w = bounds.outWidth
    val h = bounds.outHeight
    while (w / sample > maxSide || h / sample > maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: error("Gagal membaca gambar")
}

internal fun MainActivity.saveBitmapToAppImages(
    bitmap: Bitmap,
    baseName: String,
    format: Bitmap.CompressFormat,
    quality: Int
): File {
    val ext = when (format) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.WEBP -> "webp"
        else -> "jpg"
    }
    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val safe = baseName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(40).ifBlank { "image" }
    val out = File(dir, "${safe}_${System.currentTimeMillis()}.$ext")
    FileOutputStream(out).use { fos ->
        val q = quality.coerceIn(1, 100)
        val ok = when (format) {
            Bitmap.CompressFormat.WEBP -> {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, fos)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, q, fos)
                }
            }
            else -> bitmap.compress(format, q, fos)
        }
        if (!ok) error("Gagal menyimpan gambar")
    }
    return out
}

internal fun MainActivity.imagePreviewCard(): Pair<LinearLayout, ImageView> {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }
    applyInteractiveSurface(card, Ds.RADIUS_LG, 1)
    val preview = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(200))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        background = bg(if (isDarkTheme) Color.rgb(32, 32, 35) else Color.rgb(244, 246, 248), 16, line)
        visibility = View.GONE
    }
    card.addView(preview)
    return card to preview
}


// Photo Editor dipindah ke PhotoEditorPro.kt + PhotoEngine.kt (pipeline non-destruktif).

/** Fast separable box blur (radius 1..6). */
internal fun boxBlur(src: Bitmap, radius: Int): Bitmap {
    if (radius <= 0) return src
    val w = src.width
    val h = src.height
    var pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    var buffer = IntArray(w * h)
    val r = radius.coerceIn(1, 8)
    // horizontal
    for (y in 0 until h) {
        for (x in 0 until w) {
            var rs = 0; var gs = 0; var bs = 0; var asu = 0; var n = 0
            for (k in -r..r) {
                val xx = (x + k).coerceIn(0, w - 1)
                val c = pixels[y * w + xx]
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); asu += Color.alpha(c)
                n++
            }
            buffer[y * w + x] = Color.argb(asu / n, rs / n, gs / n, bs / n)
        }
    }
    // vertical
    val outPix = IntArray(w * h)
    for (x in 0 until w) {
        for (y in 0 until h) {
            var rs = 0; var gs = 0; var bs = 0; var asu = 0; var n = 0
            for (k in -r..r) {
                val yy = (y + k).coerceIn(0, h - 1)
                val c = buffer[yy * w + x]
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); asu += Color.alpha(c)
                n++
            }
            outPix[y * w + x] = Color.argb(asu / n, rs / n, gs / n, bs / n)
        }
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(outPix, 0, w, 0, 0, w, h)
    return out
}

/** Unsharp-mask style sharpen. */
internal fun sharpenBitmap(src: Bitmap, amount: Float): Bitmap {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = IntArray(w * h)
    val a = amount.coerceIn(0f, 1.5f)
    val center = 1f + 4f * a
    val edge = -a
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            fun ch(idx: Int, shift: Int) = (pixels[idx] shr shift) and 0xFF
            val i = y * w + x
            var r = (ch(i, 16) * center + ch(i - 1, 16) * edge + ch(i + 1, 16) * edge +
                ch(i - w, 16) * edge + ch(i + w, 16) * edge).toInt()
            var g = (ch(i, 8) * center + ch(i - 1, 8) * edge + ch(i + 1, 8) * edge +
                ch(i - w, 8) * edge + ch(i + w, 8) * edge).toInt()
            var b = (ch(i, 0) * center + ch(i - 1, 0) * edge + ch(i + 1, 0) * edge +
                ch(i - w, 0) * edge + ch(i + w, 0) * edge).toInt()
            r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
            out[i] = (pixels[i] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }
    // copy borders
    for (x in 0 until w) {
        out[x] = pixels[x]
        out[(h - 1) * w + x] = pixels[(h - 1) * w + x]
    }
    for (y in 0 until h) {
        out[y * w] = pixels[y * w]
        out[y * w + w - 1] = pixels[y * w + w - 1]
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.setPixels(out, 0, w, 0, 0, w, h)
    return bmp
}

internal fun applyVignette(src: Bitmap, strength: Float): Bitmap {
    val w = src.width
    val h = src.height
    val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
    val c = Canvas(out)
    val cx = w / 2f
    val cy = h / 2f
    val radius = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
    val colors = intArrayOf(Color.TRANSPARENT, Color.argb((180 * strength.coerceIn(0f, 1f)).toInt(), 0, 0, 0))
    val shader = android.graphics.RadialGradient(cx, cy, radius, colors, floatArrayOf(0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = shader
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    return out
}

// Background Remover dipindah ke BackgroundRemoverPro.kt (AI segmentasi + kuas + ekspor penuh).

/** Sample corner colors; pixels close to that average become transparent. */
internal fun removeSolidBackground(src: Bitmap, tolerance: Int): Bitmap {
    val w = src.width
    val h = src.height
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
        ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(src, 0f, 0f, null)
        }
    val pixels = IntArray(w * h)
    out.getPixels(pixels, 0, w, 0, 0, w, h)

    // Sample 8×8 from each corner
    fun sampleCorner(x0: Int, y0: Int): Triple<Int, Int, Int> {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in y0 until (y0 + 8).coerceAtMost(h)) {
            for (x in x0 until (x0 + 8).coerceAtMost(w)) {
                val c = pixels[y * w + x]
                r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                n++
            }
        }
        if (n == 0) return Triple(255, 255, 255)
        return Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
    val corners = listOf(
        sampleCorner(0, 0),
        sampleCorner((w - 8).coerceAtLeast(0), 0),
        sampleCorner(0, (h - 8).coerceAtLeast(0)),
        sampleCorner((w - 8).coerceAtLeast(0), (h - 8).coerceAtLeast(0))
    )
    val avgR = corners.map { it.first }.average().toInt()
    val avgG = corners.map { it.second }.average().toInt()
    val avgB = corners.map { it.third }.average().toInt()
    val tol2 = tolerance * tolerance * 3

    for (i in pixels.indices) {
        val c = pixels[i]
        val dr = Color.red(c) - avgR
        val dg = Color.green(c) - avgG
        val db = Color.blue(c) - avgB
        if (dr * dr + dg * dg + db * db <= tol2) {
            pixels[i] = Color.TRANSPARENT
        }
    }
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

// ---------------------------------------------------------------------------
// Estimasi hasil kompresi (bersama Image Compressor & Image Converter)
// ---------------------------------------------------------------------------

/** Hasil enkode di memori: byte persis yang akan disimpan (estimasi = hasil sebenarnya). */
internal class ImgEncoded(val data: ByteArray, val w: Int, val h: Int, val format: String, val quality: Int)

/**
 * Enkode gambar di latar belakang dengan debounce + nomor generasi, sehingga geser slider cepat
 * tidak memicu proses berulang yang menghabiskan memori/CPU. Hanya hasil permintaan terakhir dipakai.
 */
internal class ImgEstimator(private val onResult: (ImgEncoded?, String?) -> Unit) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var gen = 0
    private var pending: Runnable? = null
    var last: ImgEncoded? = null
        private set

    fun cancel() {
        gen++
        pending?.let { handler.removeCallbacks(it) }
    }

    /** [format] = "jpg" | "webp" | "png"; [targetKb] > 0 → cari kualitas tertinggi yang muat. */
    fun request(src: Bitmap, maxSide: Int, format: String, quality: Int, targetKb: Int, delayMs: Long = 300L) {
        val my = ++gen
        last = null
        pending?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            Thread {
                val r = runCatching { encodeImage(src, maxSide, format, quality, targetKb) { my != gen } }
                handler.post {
                    if (my != gen) return@post
                    r.onSuccess { last = it; onResult(it, null) }
                        .onFailure { onResult(null, if (it is OutOfMemoryError) "Memori tidak cukup — kecilkan resolusi" else it.message ?: "Gagal") }
                }
            }.start()
        }
        pending = task
        handler.postDelayed(task, delayMs)
    }
}

/** Skala ke sisi terpanjang [maxSide] (0 = asli), ratakan transparansi ke putih untuk JPG, lalu enkode. */
internal fun encodeImage(src: Bitmap, maxSide: Int, format: String, quality: Int, targetKb: Int, cancelled: () -> Boolean = { false }): ImgEncoded? {
    val longest = maxOf(src.width, src.height)
    var bmp = if (maxSide in 1 until longest) {
        val s = maxSide.toFloat() / longest
        Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    } else src
    if (format == "jpg" && bmp.hasAlpha()) {
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
        if (bmp !== src) bmp.recycle()
        bmp = flat
    }
    fun enc(q: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val ok = when (format) {
            "png" -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, out)
            else @Suppress("DEPRECATION") bmp.compress(Bitmap.CompressFormat.WEBP, q, out)
            else -> bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
        }
        if (!ok) error("Enkoder gambar gagal")
        return out.toByteArray()
    }
    try {
        if (cancelled()) return null
        if (targetKb > 0 && format != "png") {
            // Pencarian biner kualitas (maks 7 percobaan) agar ukuran <= target.
            val limit = targetKb * 1024L
            var lo = 5
            var hi = 95
            var best: Pair<Int, ByteArray>? = null
            while (lo <= hi) {
                if (cancelled()) return null
                val mid = (lo + hi) / 2
                val data = enc(mid)
                if (data.size <= limit) { best = mid to data; lo = mid + 1 } else hi = mid - 1
            }
            val chosen = best ?: (5 to enc(5))
            return ImgEncoded(chosen.second, bmp.width, bmp.height, format, chosen.first)
        }
        val q = quality.coerceIn(5, 100)
        return ImgEncoded(enc(q), bmp.width, bmp.height, format, q)
    } finally {
        if (bmp !== src) bmp.recycle()
    }
}

private fun fmtExt(format: String) = when (format) { "png" -> "png"; "webp" -> "webp"; else -> "jpg" }
private fun fmtMime(format: String) = when (format) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }

/** Simpan byte gambar yang sudah dienkode ke Galeri (Pictures/GITLS) tanpa enkode ulang. */
internal fun MainActivity.saveEncodedToGallery(enc: ImgEncoded, baseName: String): SavedImage {
    val safe = baseName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(40).ifBlank { "gambar" }
    val name = "${safe}_${System.currentTimeMillis()}.${fmtExt(enc.format)}"
    val mime = fmtMime(enc.format)
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GITLS")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Penyimpanan galeri tidak tersedia")
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(enc.data) } ?: error("Tidak bisa membuka penyimpanan")
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw e
        }
        return SavedImage(uri, "Pictures/GITLS/$name", enc.data.size.toLong(), mime)
    }
    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, name)
    file.writeBytes(enc.data)
    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    return SavedImage(uri, "Folder aplikasi/Pictures/$name", file.length(), mime)
}

/** Dekode byte hasil untuk pratinjau (dibatasi agar hemat memori). */
private fun decodePreview(data: ByteArray, maxSide: Int = 1600): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Baris tombol segmen sederhana (gaya tombol tool bawaan) — mengembalikan fungsi untuk menandai pilihan aktif. */
private fun MainActivity.segRow(options: List<String>, active: String, onPick: (String) -> Unit): Pair<LinearLayout, (String) -> Unit> {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val buttons = LinkedHashMap<String, Button>()
    val mark: (String) -> Unit = { key -> buttons.forEach { (k, b) -> if (k == key) styleAsPrimary(b) else styleAsSecondary(b) } }
    options.forEachIndexed { i, key ->
        val b = button(key) { mark(key); onPick(key) }
        buttons[key] = b
        row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(5) })
    }
    mark(active)
    return row to mark
}

private fun MainActivity.sliderRow(labelOf: (Int) -> String, max: Int, progress: Int, onChange: (Int) -> Unit): Pair<TextView, SeekBar> {
    val l = label(labelOf(progress), 12f).apply { setPadding(0, dp(10), 0, 0) }
    val sb = SeekBar(this).apply {
        this.max = max
        this.progress = progress
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) { l.text = labelOf(p); if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }
    content.addView(l)
    content.addView(sb)
    return l to sb
}

private fun pctSaved(src: Long, out: Long): String =
    if (src <= 0) "" else if (out <= src) String.format(Locale.US, " · hemat %.0f%%", (src - out) * 100.0 / src)
    else String.format(Locale.US, " · %.0f%% lebih besar", (out - src) * 100.0 / src)

// ---------------------------------------------------------------------------
// Image Compressor
// ---------------------------------------------------------------------------

internal fun MainActivity.imageCompressorTool() {
    clearPage("Image Compressor")
    title.text = "Image Compressor"
    action.text = "?"
    action.textSize = 20f
    action.isClickable = true
    action.setOnClickListener {
        themedDialog()
            .setTitle("Image Compressor")
            .setMessage(
                "Kompres gambar dengan mengatur kualitas JPG/WebP, batas sisi terpanjang, atau target ukuran (KB).\n\n" +
                    "Perkiraan ukuran dihitung dari hasil enkode sebenarnya, dan byte yang sama yang disimpan — " +
                    "jadi ukuran file akhir sama dengan perkiraan. Ketuk Asli/Hasil untuk membandingkan kualitas. File asli tidak diubah."
            )
            .setPositiveButton("OK", null)
            .show()
    }
    back.setOnClickListener { navigateBack() }

    content.addView(subLabel("Kurangi ukuran file — perkiraan hasil & pratinjau kualitas tampil sebelum disimpan.", 12f).apply {
        setPadding(0, 0, 0, dp(10))
    })

    val (previewCard, preview) = imagePreviewCard()
    val status = label("Belum ada gambar", 13f).apply { setPadding(dp(4), dp(8), dp(4), dp(2)) }
    val estimate = label("", 13f, true).apply { setPadding(dp(4), dp(2), dp(4), dp(2)); visibility = View.GONE }
    previewCard.addView(status)
    previewCard.addView(estimate)
    content.addView(previewCard)

    var source: Bitmap? = null
    var resultPreview: Bitmap? = null
    var sourceBytes = -1L
    var quality = 75
    var maxSide = 1600
    var format = "jpg"
    var targetKb = 0
    var showResult = true
    var name = "compressed"

    fun showPreview() {
        val bmp = if (showResult) resultPreview ?: source else source
        if (bmp != null) { preview.setImageBitmap(bmp); preview.visibility = View.VISIBLE }
    }
    val (viewRow, markView) = segRow(listOf("Asli", "Hasil"), "Hasil") { k -> showResult = k == "Hasil"; showPreview() }
    viewRow.setPadding(0, dp(6), 0, 0)
    previewCard.addView(viewRow)

    val estimator = ImgEstimator { enc, err ->
        if (enc == null) { estimate.text = err ?: ""; return@ImgEstimator }
        estimate.visibility = View.VISIBLE
        val q = if (targetKb > 0) " · kualitas otomatis ${enc.quality}%" else ""
        estimate.text = "Hasil: ${enc.w}×${enc.h} · ${formatImageBytes(enc.data.size.toLong())}${pctSaved(sourceBytes, enc.data.size.toLong())}$q"
        resultPreview?.recycle()
        resultPreview = runCatching { decodePreview(enc.data) }.getOrNull()
        showPreview()
    }
    fun refresh() {
        val src = source ?: return
        estimate.visibility = View.VISIBLE
        estimate.text = "Menghitung perkiraan…"
        estimator.request(src, maxSide, format, quality, targetKb)
    }

    content.addView(button("Pilih gambar") {
        pickImageForTool(1222) { uri ->
            if (uri == null) return@pickImageForTool
            persistUriPermissionsSafely(uri, wantWrite = false)
            Thread {
                try {
                    val size = runCatching {
                        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    }.getOrDefault(-1L)
                    val orig = imageOriginalSize(uri)
                    val bmp = decodeOrientedBitmap(uri, 4096)
                    val display = runCatching { displayNameFor(uri) }.getOrDefault("gambar")
                    runOnUiThread {
                        estimator.cancel()
                        source?.recycle()
                        resultPreview?.recycle(); resultPreview = null
                        source = bmp
                        sourceBytes = size
                        name = "$display-kompres"
                        val srcInfo = if (size > 0) " · ${formatImageBytes(size)}" else ""
                        status.text = "Asli: ${sizeNote(orig, bmp)}$srcInfo"
                        showResult = true; markView("Hasil")
                        showPreview()
                        refresh()
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Gagal: ${e.message}") }
                }
            }.start()
        }
    })

    val (_, qSeek) = sliderRow({ "Kualitas: ${it.coerceAtLeast(5)}%" }, 100, quality) { quality = it.coerceAtLeast(5); targetKb = 0; refresh() }
    sliderRow({ if (it >= 37) "Sisi terpanjang: asli" else "Sisi terpanjang maks: ${400 + it * 100} px" }, 37, 12) {
        maxSide = if (it >= 37) 0 else 400 + it * 100
        refresh()
    }

    content.addView(label("Format", 12f).apply { setPadding(0, dp(10), 0, dp(4)) })
    val (fmtRow, _) = segRow(listOf("JPG", "WebP"), "JPG") { k -> format = if (k == "WebP") "webp" else "jpg"; refresh() }
    content.addView(fmtRow)

    content.addView(label("Target ukuran (opsional)", 12f).apply { setPadding(0, dp(12), 0, dp(4)) })
    val targetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val targetField = edit("mis. 200").apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        setSingleLine(true)
    }
    targetRow.addView(targetField, LinearLayout.LayoutParams(0, -2, 1f))
    targetRow.addView(label("KB", 13f).apply { setPadding(dp(8), 0, dp(8), 0) })
    targetRow.addView(button("Terapkan") {
        targetKb = targetField.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(5, 50_000) ?: 0
        if (targetKb == 0) toast("Target kosong — memakai slider kualitas")
        refresh()
    })
    content.addView(targetRow)
    content.addView(subLabel("Dengan target, kualitas tertinggi yang muat dicari otomatis. Geser slider kualitas untuk kembali manual.", 11f).apply { setPadding(0, dp(4), 0, 0) })
    qSeek.contentDescription = "Kualitas kompresi"

    pinToolAction(button("Kompres & simpan ke Galeri") {
        val src = source
        if (src == null) {
            toast("Pilih gambar dulu")
            return@button
        }
        status.text = "Menyimpan…"
        val ready = estimator.last
        val q = quality; val side = maxSide; val f = format; val t = targetKb
        Thread {
            try {
                val enc = ready ?: encodeImage(src, side, f, q, t) ?: error("Dibatalkan")
                val saved = saveEncodedToGallery(enc, name)
                runOnUiThread {
                    status.text = "Tersimpan: ${saved.label}\n${enc.w}×${enc.h} · ${formatImageBytes(saved.bytes)}${pctSaved(sourceBytes, saved.bytes)}"
                    toast("Kompres selesai")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    status.text = "Gagal: ${e.message}"
                    toast("Gagal: ${e.message}")
                }
            }
        }.start()
    })
}

// ---------------------------------------------------------------------------
// Image Converter — JPG / PNG / WebP / BMP
// ---------------------------------------------------------------------------

internal fun MainActivity.imageConverterTool() {
    clearPage("Image Converter")
    title.text = "Image Converter"
    action.text = "?"
    action.textSize = 20f
    action.isClickable = true
    action.setOnClickListener {
        themedDialog()
            .setTitle("Image Converter")
            .setMessage("Konversi gambar ke JPG, PNG, WebP, atau BMP. Transparansi PNG hilang saat ke JPG/BMP (latar putih). Perkiraan ukuran tampil sebelum disimpan.")
            .setPositiveButton("OK", null)
            .show()
    }
    back.setOnClickListener { navigateBack() }

    content.addView(subLabel("Ubah format gambar: JPG, PNG, WebP, atau BMP — dengan perkiraan ukuran hasil.", 12f).apply {
        setPadding(0, 0, 0, dp(10))
    })

    val (previewCard, preview) = imagePreviewCard()
    val status = label("Belum ada gambar", 13f).apply { setPadding(dp(4), dp(8), dp(4), dp(2)) }
    val estimate = label("", 13f, true).apply { setPadding(dp(4), dp(2), dp(4), dp(2)); visibility = View.GONE }
    previewCard.addView(status)
    previewCard.addView(estimate)
    content.addView(previewCard)

    var source: Bitmap? = null
    var sourceBytes = -1L
    var target = "JPG"
    var quality = 90
    var name = "converted"

    val estimator = ImgEstimator { enc, err ->
        estimate.visibility = View.VISIBLE
        estimate.text = if (enc == null) err ?: "" else "Perkiraan ${target}: ${formatImageBytes(enc.data.size.toLong())}${pctSaved(sourceBytes, enc.data.size.toLong())}"
    }
    fun refresh() {
        val src = source ?: return
        estimate.visibility = View.VISIBLE
        if (target == "BMP") {
            // BMP 24-bit tanpa kompresi: ukuran bisa dihitung pasti.
            estimator.cancel()
            val bytes = 54L + ((24L * src.width + 31) / 32) * 4 * src.height
            estimate.text = "Perkiraan BMP: ${formatImageBytes(bytes)}${pctSaved(sourceBytes, bytes)}"
            return
        }
        estimate.text = "Menghitung perkiraan…"
        estimator.request(src, 0, when (target) { "PNG" -> "png"; "WebP" -> "webp"; else -> "jpg" }, quality, 0)
    }

    content.addView(button("Pilih gambar") {
        pickImageForTool(1223) { uri ->
            if (uri == null) return@pickImageForTool
            persistUriPermissionsSafely(uri, wantWrite = false)
            Thread {
                try {
                    val size = runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L }.getOrDefault(-1L)
                    val mime = runCatching { contentResolver.getType(uri) }.getOrNull()?.substringAfter('/')?.uppercase(Locale.ROOT) ?: "?"
                    val orig = imageOriginalSize(uri)
                    val bmp = decodeOrientedBitmap(uri, 4096)
                    val display = runCatching { displayNameFor(uri) }.getOrDefault("gambar")
                    runOnUiThread {
                        estimator.cancel()
                        source?.recycle()
                        source = bmp
                        sourceBytes = size
                        name = display
                        preview.setImageBitmap(bmp)
                        preview.visibility = View.VISIBLE
                        status.text = "Asli: $mime · ${sizeNote(orig, bmp)}" + if (size > 0) " · ${formatImageBytes(size)}" else ""
                        refresh()
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Gagal: ${e.message}") }
                }
            }.start()
        }
    })

    content.addView(label("Format tujuan", 13f, true).apply { setPadding(0, dp(12), 0, dp(6)) })
    val (fmtRow, _) = segRow(listOf("JPG", "PNG", "WebP", "BMP"), target) { k -> target = k; refresh() }
    content.addView(fmtRow)
    sliderRow({ "Kualitas JPG/WebP: ${it.coerceAtLeast(5)}%" }, 100, quality) { quality = it.coerceAtLeast(5); refresh() }

    pinToolAction(button("Konversi & simpan") {
        val src = source
        if (src == null) {
            toast("Pilih gambar dulu")
            return@button
        }
        status.text = "Mengonversi…"
        val ready = estimator.last
        val t = target; val q = quality
        Thread {
            try {
                val label = if (t == "BMP") {
                    val flat = if (src.hasAlpha()) {
                        Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) } }
                    } else src
                    val file = saveAsBmp(flat, name)
                    if (flat !== src) flat.recycle()
                    "${file.name} (${formatImageBytes(file.length())})"
                } else {
                    val f = when (t) { "PNG" -> "png"; "WebP" -> "webp"; else -> "jpg" }
                    val enc = ready?.takeIf { it.format == f } ?: encodeImage(src, 0, f, q, 0) ?: error("Dibatalkan")
                    val saved = saveEncodedToGallery(enc, name)
                    "${saved.label} (${formatImageBytes(saved.bytes)})"
                }
                runOnUiThread {
                    status.text = "Tersimpan: $label"
                    toast("Konversi selesai")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    status.text = "Gagal: ${e.message}"
                    toast("Gagal: ${e.message}")
                }
            }
        }.start()
    })
}

/** Write a simple 24-bit BMP (no compression). */
internal fun MainActivity.saveAsBmp(bitmap: Bitmap, baseName: String): File {
    val w = bitmap.width
    val h = bitmap.height
    val rowSize = ((24 * w + 31) / 32) * 4
    val pixelSize = rowSize * h
    val fileSize = 54 + pixelSize
    val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val safe = baseName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(40).ifBlank { "image" }
    val out = File(dir, "${safe}_${System.currentTimeMillis()}.bmp")
    FileOutputStream(out).use { fos ->
        val header = java.nio.ByteBuffer.allocate(54).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put('B'.code.toByte()); header.put('M'.code.toByte())
        header.putInt(fileSize)
        header.putInt(0)
        header.putInt(54)
        header.putInt(40) // DIB size
        header.putInt(w)
        header.putInt(h)
        header.putShort(1) // planes
        header.putShort(24) // bpp
        header.putInt(0) // compression
        header.putInt(pixelSize)
        header.putInt(2835); header.putInt(2835)
        header.putInt(0); header.putInt(0)
        fos.write(header.array())
        val row = ByteArray(rowSize)
        for (y in h - 1 downTo 0) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val i = x * 3
                row[i] = Color.blue(c).toByte()
                row[i + 1] = Color.green(c).toByte()
                row[i + 2] = Color.red(c).toByte()
                x++
            }
            // pad already zeroed
            for (p in w * 3 until rowSize) row[p] = 0
            fos.write(row)
        }
    }
    return out
}
