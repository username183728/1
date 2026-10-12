package com.example.aidetest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pemuat thumbnail filmstrip & waveform untuk timeline Video Editor.
 * Semua decoding di thread latar (satu thread per jenis), memori dibatasi LruCache,
 * dan permintaan yang sama tidak diulang (termasuk yang gagal).
 */
internal object VeAssets {
    private const val BUCKET_MS = 500L
    private val main = Handler(Looper.getMainLooper())
    private val frameExec = Executors.newSingleThreadExecutor { r -> Thread(r, "ve-frames").apply { isDaemon = true } }
    private val waveExec = Executors.newSingleThreadExecutor { r -> Thread(r, "ve-waves").apply { isDaemon = true } }

    private val frames = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pendingFrames = HashSet<String>()
    private val failedFrames = HashSet<String>()
    private val waves = HashMap<String, ShortArray>()
    private val waveMax = HashMap<String, Int>()
    private val pendingWaves = HashSet<String>()
    private val failedWaves = HashSet<String>()

    // Hanya disentuh dari thread "ve-frames".
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>()
    private val aspects = HashMap<String, FloatArray>()

    private fun bucket(tMs: Long): Long = (tMs.coerceAtLeast(0L) / BUCKET_MS) * BUCKET_MS

    private fun key(uri: Uri, bucket: Long, h: Int, image: Boolean): String =
        if (image) "$uri|img|$h" else "$uri|$bucket|$h"

    fun cachedFrame(uri: Uri, isImage: Boolean, tMs: Long, h: Int): Bitmap? =
        frames.get(key(uri, bucket(tMs), h, isImage))?.takeIf { !it.isRecycled }

    /** Minta satu frame (tinggi [h] px). [onReady] dipanggil di thread utama setelah masuk cache. */
    fun requestFrame(ctx: Context, uri: Uri, isImage: Boolean, tMs: Long, h: Int, onReady: () -> Unit) {
        val b = bucket(tMs)
        val k = key(uri, b, h, isImage)
        if (frames.get(k) != null || failedFrames.contains(k) || !pendingFrames.add(k)) return
        val app = ctx.applicationContext
        frameExec.execute {
            val bmp = runCatching { if (isImage) decodeImage(app, uri, h) else decodeFrame(app, uri, b, h) }.getOrNull()
            main.post {
                pendingFrames.remove(k)
                if (bmp != null) {
                    frames.put(k, bmp)
                    onReady()
                } else failedFrames.add(k)
            }
        }
    }

    private fun decodeFrame(ctx: Context, uri: Uri, tMs: Long, h: Int): Bitmap? {
        val id = uri.toString()
        var r = retrievers[id]
        if (r == null) {
            val created = MediaMetadataRetriever()
            try {
                created.setDataSource(ctx, uri)
            } catch (e: Throwable) {
                runCatching { created.release() }
                return null
            }
            r = created
            retrievers[id] = created
            while (retrievers.size > 4) {
                val first = retrievers.entries.first()
                runCatching { first.value.release() }
                retrievers.remove(first.key)
            }
            val w0 = created.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h0 = created.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = created.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val aspect = if (w0 > 0 && h0 > 0) (if (rot % 180 != 0) h0.toFloat() / w0 else w0.toFloat() / h0) else 1.78f
            aspects[id] = floatArrayOf(aspect)
        }
        val timeUs = tMs * 1000L
        val aspect = (aspects[id]?.firstOrNull() ?: 1.78f).coerceIn(0.3f, 4f)
        val frame: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
            val w = min(h * 2, max(1, (h * aspect).roundToInt()))
            r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        } else {
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleToHeight(it, h) }
        }
        return frame
    }

    private fun scaleToHeight(src: Bitmap, h: Int): Bitmap {
        if (src.height <= h) return src
        val w = max(1, (src.width.toFloat() * h / src.height).roundToInt())
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Decode gambar dengan orientasi EXIF, tinggi akhir ≈ [h]. */
    fun decodeImage(ctx: Context, uri: Uri, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= h) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val oriented = applyExif(ctx, uri, raw)
        return scaleToHeight(oriented, h)
    }

    /** Decode dengan sisi terpanjang ≤ [maxSide] (orientasi EXIF diterapkan). */
    fun decodeFit(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        // Batas memori: foto 48–50 MP tidak didekode penuh (~200 MB) walau sisi terpanjangnya < 2× maxSide.
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 16_000_000L) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val oriented = applyExif(ctx, uri, raw)
        val longest = max(oriented.width, oriented.height)
        if (longest <= maxSide) return oriented
        val s = maxSide.toFloat() / longest
        val out = Bitmap.createScaledBitmap(oriented, max(1, (oriented.width * s).roundToInt()), max(1, (oriented.height * s).roundToInt()), true)
        if (out !== oriented) oriented.recycle()
        return out
    }

    /** Nilai tag orientasi EXIF (ExifInterface.ORIENTATION_*), normal bila tidak ada / tak terbaca. */
    fun exifOrientation(ctx: Context, uri: Uri): Int = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** Putar bitmap sesuai tag EXIF (foto kamera sering tersimpan menyamping). */
    fun applyExif(ctx: Context, uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = exifOrientation(ctx, uri)
        val m = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
        }.getOrDefault(bmp)
    }

    // ------------------------------------------------------------------ waveform

    fun cachedWave(uri: Uri): ShortArray? = waves[uri.toString()]

    /** Puncak terbesar pada waveform (≥ 1) untuk normalisasi tampilan. */
    fun waveMax(uri: Uri): Int = waveMax[uri.toString()] ?: 1

    fun requestWave(ctx: Context, uri: Uri, onReady: () -> Unit) {
        val k = uri.toString()
        if (waves.containsKey(k) || failedWaves.contains(k) || !pendingWaves.add(k)) return
        val app = ctx.applicationContext
        waveExec.execute {
            val peaks = runCatching { VeAudioMix.peaks(app, uri) }.getOrDefault(ShortArray(0))
            main.post {
                pendingWaves.remove(k)
                if (peaks.isEmpty()) failedWaves.add(k)
                else {
                    var mx = 1
                    for (v in peaks) if (v > mx) mx = v.toInt()
                    waves[k] = peaks
                    waveMax[k] = mx
                    onReady()
                }
            }
        }
    }

    /** Lepas retriever & cache (dipanggil saat editor ditutup). */
    fun clear() {
        frames.evictAll()
        pendingFrames.clear()
        failedFrames.clear()
        frameExec.execute {
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
            aspects.clear()
        }
    }
}
