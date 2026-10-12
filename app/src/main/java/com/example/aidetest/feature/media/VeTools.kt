package com.example.aidetest

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

// ============================================================================ impor media

internal class VeProbe(val media: VeMedia, val poster: Bitmap?)

internal object VeImport {
    fun probeVideo(ctx: Context, uri: Uri, name: String): VeProbe? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) return null
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(500L) ?: 500L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val frame = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val poster = frame?.let { f ->
                val tw = 160
                val th = (tw.toFloat() * f.height / max(1, f.width)).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(f, tw, th, true)
            }
            VeProbe(VeMedia(uri, name, false, dur, w, h, rot), poster)
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Foto yang tersimpan menyamping (EXIF) atau terlalu besar (> 4096 px) disalin menjadi berkas tegak
     * di folder aplikasi agar pratinjau dan hasil render konsisten; selain itu Uri asli dipakai.
     */
    fun normalizeImage(ctx: Context, uri: Uri): Uri {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return uri
        val orientation = VeAssets.exifOrientation(ctx, uri)
        val rotated = orientation != android.media.ExifInterface.ORIENTATION_NORMAL && orientation != android.media.ExifInterface.ORIENTATION_UNDEFINED
        val tooBig = max(bounds.outWidth, bounds.outHeight) > 4096
        // Media3 hanya membaca JPEG/PNG/WebP (+BMP) sebagai gambar: GIF, HEIC, AVIF, dll. dikonversi dulu.
        val mime = bounds.outMimeType ?: ""
        val supported = mime == "image/jpeg" || mime == "image/png" || mime == "image/webp"
        if (!rotated && !tooBig && supported) return uri
        val bmp = VeAssets.decodeFit(ctx, uri, 4096) ?: return uri
        val png = mime == "image/png" || mime == "image/gif" || mime == "image/webp" || mime == "image/avif" || mime == "image/heif" || mime == "image/heic"
        val dir = File(ctx.filesDir, "ve_media").apply { mkdirs() }
        val f = File(dir, "img_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}.${if (png) "png" else "jpg"}")
        return try {
            FileOutputStream(f).use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 94, it) }
            Uri.fromFile(f)
        } catch (e: Throwable) {
            f.delete()
            uri
        } finally {
            bmp.recycle()
        }
    }

    fun probeImage(ctx: Context, uri: Uri, name: String): VeProbe? {
        return try {
            val final = normalizeImage(ctx, uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(final)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val poster = VeAssets.decodeFit(ctx, final, 200)
            VeProbe(VeMedia(final, name, true, 3000L, bounds.outWidth, bounds.outHeight, 0), poster)
        } catch (e: Throwable) {
            null
        }
    }

    /** Durasi audio (ms); 0 bila tidak terbaca. */
    fun audioDuration(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Throwable) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    fun hasAudioTrack(ctx: Context, uri: Uri): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
        } catch (e: Throwable) {
            false
        } finally {
            runCatching { r.release() }
        }
    }
}

// ============================================================================ analisis (auto-cut)

internal object VeAnalysis {
    /**
     * Profil energi audio (dB per 50 ms) dari [startMs, endMs) sebuah berkas.
     * @return null bila dibatalkan atau audio tidak terbaca.
     */
    fun rmsProfile(ctx: Context, uri: Uri, startMs: Long, endMs: Long, cancelled: () -> Boolean, progress: (Int) -> Unit): FloatArray? {
        val stream = VeAudioStream(ctx, uri, startMs, endMs)
        if (!stream.open()) return null
        val out = ArrayList<Float>()
        val totalFrames = max(1L, (endMs - startMs) * VeMixer.RATE / 1000L)
        var done = 0L
        try {
            while (true) {
                if (cancelled()) return null
                val chunk = stream.read(VeMixer.RATE)
                if (chunk.isEmpty()) break
                for (v in VeSilence.rmsDb(chunk, VeMixer.RATE * 2, 50)) out.add(v)
                done += chunk.size / 2
                progress(min(100L, done * 100L / totalFrames).toInt())
                if (chunk.size / 2 < VeMixer.RATE) break
            }
        } finally {
            stream.close()
        }
        return if (out.isEmpty()) null else out.toFloatArray()
    }

    /**
     * Waktu potong adegan (ms, relatif ke [startMs]) dari perubahan histogram frame.
     * @return null bila dibatalkan / video tidak terbaca.
     */
    fun sceneCuts(
        ctx: Context, uri: Uri, startMs: Long, endMs: Long, threshold: Float, minSceneMs: Long,
        cancelled: () -> Boolean, progress: (Int) -> Unit
    ): List<Long>? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
        } catch (e: Throwable) {
            runCatching { r.release() }
            return null
        }
        try {
            val span = max(1L, endMs - startMs)
            val step = max(400L, span / 500L)
            val times = ArrayList<Long>()
            val hists = ArrayList<FloatArray>()
            var t = startMs
            while (t < endMs) {
                if (cancelled()) return null
                val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, 64, 36)
                } else {
                    r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)?.let { f ->
                        Bitmap.createScaledBitmap(f, 64, 36, true).also { if (it !== f) f.recycle() }
                    }
                }
                if (bmp != null) {
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    hists.add(VeScene.histogram(px))
                    times.add(t - startMs)
                    bmp.recycle()
                }
                progress(min(100L, (t - startMs) * 100L / span).toInt())
                t += step
            }
            if (hists.size < 2) return emptyList()
            return VeScene.cuts(times.toLongArray(), hists, threshold, minSceneMs)
        } finally {
            runCatching { r.release() }
        }
    }
}

// ============================================================================ rekam suara

internal class VeVoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val recording: Boolean get() = rec != null

    /** @return pesan galat, atau null bila perekaman dimulai. */
    @Suppress("DEPRECATION")
    fun start(): String? {
        if (rec != null) return null
        val dir = File(ctx.filesDir, "ve_media").apply { mkdirs() }
        val f = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Throwable) {
            runCatching { r.release() }
            f.delete()
            return e.message ?: "Mikrofon tidak bisa dipakai"
        }
        rec = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
        return null
    }

    fun level(): Float = runCatching { (rec?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    fun elapsedMs(): Long = if (rec != null) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Hentikan & kembalikan berkas + durasi; null bila terlalu singkat / gagal. */
    fun stop(): Pair<File, Long>? {
        val r = rec ?: return null
        val f = file
        val dur = elapsedMs()
        rec = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || f == null || dur < 300L) {
            f?.delete()
            return null
        }
        return f to dur
    }

    fun cancel() {
        val r = rec
        val f = file
        rec = null
        file = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        f?.delete()
    }
}

// ============================================================================ teks ke suara

/** Pembungkus TextToSpeech: bahasa, kecepatan, nada → berkas WAV (mesin TTS perangkat; tanpa unduhan). */
internal class VeTts(ctx: Context, private val onReady: (Boolean) -> Unit) {
    private var engine: android.speech.tts.TextToSpeech? = null
    private var ready = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        engine = android.speech.tts.TextToSpeech(ctx.applicationContext) { status ->
            ready = status == android.speech.tts.TextToSpeech.SUCCESS
            main.post { onReady(ready) }
        }
    }

    val isReady: Boolean get() = ready

    /** @return true bila bahasa tersedia (data suara terpasang). */
    fun setLanguage(locale: java.util.Locale): Boolean {
        val e = engine ?: return false
        val r = runCatching { e.setLanguage(locale) }.getOrDefault(android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED)
        return r >= android.speech.tts.TextToSpeech.LANG_AVAILABLE
    }

    fun maxChars(): Int = runCatching { android.speech.tts.TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(3900).coerceAtMost(3900)

    /** Sintesis ke [out] (WAV). [done] dipanggil di thread utama: true bila berkas terisi. */
    fun synthesize(text: String, rate: Float, pitch: Float, out: File, done: (Boolean) -> Unit) {
        val e = engine
        if (e == null || !ready) { done(false); return }
        runCatching { e.setSpeechRate(rate.coerceIn(0.3f, 3f)) }
        runCatching { e.setPitch(pitch.coerceIn(0.3f, 2f)) }
        val id = "ve_" + System.currentTimeMillis()
        e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { done(out.exists() && out.length() > 1000L) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { done(false) } }
        })
        val r = e.synthesizeToFile(text, android.os.Bundle(), out, id)
        if (r != android.speech.tts.TextToSpeech.SUCCESS) done(false)
    }

    fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}

// ============================================================================ publikasi ke penyimpanan publik

internal class VePublished(val uri: Uri, val label: String, val bytes: Long, val mime: String, val file: File?)

internal object VePublish {
    private class Target(val collection: Uri, val dir: String, val mime: String)

    private fun safe(name: String): String = name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(48).trim().ifBlank { "berkas" }

    private fun targetFor(ext: String): Target? = when (ext.lowercase(java.util.Locale.US)) {
        "mp4", "m4v" -> Target(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, "video/mp4")
        "m4a" -> Target(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC, "audio/mp4")
        "aac" -> Target(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC, "audio/aac")
        "wav" -> Target(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC, "audio/x-wav")
        "mp3" -> Target(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC, "audio/mpeg")
        "jpg", "jpeg" -> Target(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, "image/jpeg")
        "png" -> Target(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, "image/png")
        "webp" -> Target(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, "image/webp")
        "gif" -> Target(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, "image/gif")
        "svg" -> Target(MediaStore.Files.getContentUri("external"), Environment.DIRECTORY_DOCUMENTS, "image/svg+xml")
        "pdf" -> Target(MediaStore.Files.getContentUri("external"), Environment.DIRECTORY_DOCUMENTS, "application/pdf")
        "srt" -> Target(MediaStore.Files.getContentUri("external"), Environment.DIRECTORY_DOCUMENTS, "application/x-subrip")
        "txt" -> Target(MediaStore.Files.getContentUri("external"), Environment.DIRECTORY_DOCUMENTS, "text/plain")
        else -> null
    }

    /** Salin [write] ke koleksi publik (Android 10+): folder <jenis>/GITLS. Mengembalikan null bila tidak berlaku. */
    private fun insert(ctx: Context, target: Target, name: String, write: (java.io.OutputStream) -> Unit): VePublished? {
        if (Build.VERSION.SDK_INT < 29) return insertLegacy(ctx, target, name, write)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.dir + "/GITLS")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(target.collection, values) ?: error("Penyimpanan publik tidak tersedia")
        var bytes = 0L
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                val counting = object : java.io.OutputStream() {
                    override fun write(b: Int) { os.write(b); bytes++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { os.write(b, off, len); bytes += len }
                    override fun flush() { os.flush() }
                }
                write(counting)
                counting.flush()
            } ?: error("Tidak bisa membuka penyimpanan")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            throw e
        }
        return VePublished(uri, target.dir + "/GITLS/" + name, bytes, target.mime, null)
    }

    /**
     * OPEN-11: Android 7–9. Tulis langsung ke folder publik <jenis>/GITLS bila izin
     * WRITE_EXTERNAL_STORAGE sudah diberikan (diminta lewat [MainActivity.ensureLegacyStorageAccess]),
     * lalu daftarkan ke MediaStore agar muncul di Galeri dan dapat content:// URI.
     * Tanpa izin → null (hasil tetap di folder aplikasi, pemanggil memberi tahu pengguna).
     */
    @Suppress("DEPRECATION")
    private fun insertLegacy(ctx: Context, target: Target, name: String, write: (java.io.OutputStream) -> Unit): VePublished? {
        if (!hasLegacyWriteAccess(ctx)) return null
        val dir = File(Environment.getExternalStoragePublicDirectory(target.dir), "GITLS")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        var out = File(dir, name)
        var i = 1
        while (out.exists()) { out = File(dir, "${name.substringBeforeLast('.')}_$i.${name.substringAfterLast('.', "bin")}"); i++ }
        try {
            FileOutputStream(out).use { write(it) }
        } catch (e: Throwable) {
            runCatching { out.delete() }
            throw e
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, out.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, out.name)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mime)
            put(MediaStore.MediaColumns.SIZE, out.length())
        }
        val uri = runCatching { ctx.contentResolver.insert(target.collection, values) }.getOrNull()
        if (uri == null) {
            // MediaStore menolak: jangan tinggalkan berkas tanpa URI yang aman dibagikan.
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), arrayOf(target.mime), null)
            return VePublished(androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out).let { it },
                target.dir + "/GITLS/" + out.name, out.length(), target.mime, out).takeIf { runCatching { it.uri }.isSuccess }
        }
        return VePublished(uri, target.dir + "/GITLS/" + out.name, out.length(), target.mime, null)
    }

    /** True bila Android 10+ (tidak perlu izin) atau izin tulis penyimpanan lama sudah diberikan. */
    fun hasLegacyWriteAccess(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 || androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Salin berkas hasil [src] ke penyimpanan publik sesuai ekstensinya (video → Movies, audio → Music,
     * gambar → Pictures, SVG/SRT/TXT → Documents; semuanya di subfolder GITLS).
     * @return null bila Android 7–9 tanpa izin penyimpanan (tetap di folder aplikasi) atau ekstensi tak dikenal.
     */
    fun copy(ctx: Context, src: File, deleteSource: Boolean = false): VePublished? {
        val target = targetFor(src.extension) ?: return null
        val pub = insert(ctx, target, src.name) { os -> src.inputStream().use { it.copyTo(os, 256 * 1024) } } ?: return null
        if (deleteSource) runCatching { src.delete() }
        return pub
    }

    /**
     * ZIP ditulis streaming ke Documents/GITLS (Android 10+, atau 7–9 dengan izin); selain itu ke folder aplikasi.
     * [write] harus menulis seluruh ZIP ke stream yang diberikan. Bila [write] melempar, berkas parsial dihapus.
     */
    fun zipStream(ctx: Context, baseName: String, write: (java.io.OutputStream) -> Unit): VePublished {
        val name = "${safe(baseName)}_${System.currentTimeMillis()}.zip"
        val pub = insert(ctx, Target(MediaStore.Files.getContentUri("external"), Environment.DIRECTORY_DOCUMENTS, "application/zip"), name, write)
        if (pub != null) return pub
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        try {
            f.outputStream().buffered(256 * 1024).use { write(it) }
        } catch (e: Throwable) {
            runCatching { f.delete() }
            throw e
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return VePublished(uri, "Folder aplikasi/Documents/$name", f.length(), "application/zip", f)
    }

    /** Video hasil render → Movies/GITLS (Android 10+, atau 7–9 dengan izin); selain itu folder aplikasi. */
    fun video(ctx: Context, src: File, baseName: String): VePublished {
        val name = "${safe(baseName)}_${System.currentTimeMillis()}.mp4"
        val pub = insert(ctx, Target(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, "video/mp4"), name) { os ->
            src.inputStream().use { it.copyTo(os, 256 * 1024) }
        }
        if (pub != null) {
            runCatching { src.delete() }
            return pub
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", src)
        return VePublished(uri, "Folder aplikasi/Movies/${src.name}", src.length(), "video/mp4", src)
    }

    /** Berkas teks (mis. SRT) → Documents/GITLS (Android 10+) atau folder aplikasi. */
    fun text(ctx: Context, content: String, baseName: String, ext: String, mime: String): VePublished {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val name = "${safe(baseName)}_${System.currentTimeMillis()}.$ext"
        val pub = runCatching { insert(ctx, Target(MediaStore.Files.getContentUri("external"), Environment.DIRECTORY_DOCUMENTS, mime), name) { os -> os.write(bytes) } }.getOrNull()
        if (pub != null) return pub
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        return VePublished(uri, "Folder aplikasi/Documents/$name", f.length(), mime, f)
    }
}

// ============================================================================ pratinjau warna & sampul

internal object VePreviewFx {
    /** Perkiraan filter/penyesuaian klip untuk pratinjau (render penuh memakai efek Media3). */
    fun clipMatrix(clip: VeClip): ColorMatrix? {
        if (!clip.hasColorEdits()) return null
        val cm = ColorMatrix()
        fun sat(v: Float) { cm.postConcat(ColorMatrix().apply { setSaturation(v) }) }
        fun scale(r: Float, g: Float, b: Float) { cm.postConcat(ColorMatrix().apply { setScale(r, g, b, 1f) }) }
        fun contrast(c: Float) {
            val k = 1f + c
            val t = 128f * (1f - k)
            cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        }
        fun bright(b: Float) {
            val t = b * 255f
            cm.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, t, 0f, 1f, 0f, 0f, t, 0f, 0f, 1f, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        }
        when (clip.filter) {
            "bw" -> sat(0f)
            "invert" -> cm.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
            "warm" -> scale(1.10f, 1.02f, 0.86f)
            "cool" -> scale(0.88f, 1.0f, 1.12f)
            "vivid" -> { sat(1.35f); contrast(0.12f) }
            "fade" -> { sat(0.7f); contrast(-0.2f); bright(0.06f) }
        }
        if (clip.temperature != 0f) {
            val t = clip.temperature.coerceIn(-1f, 1f)
            scale(1f + 0.14f * t, 1f + 0.02f * t, 1f - 0.18f * t)
        }
        if (clip.saturation != 0f) sat(1f + clip.saturation / 100f)
        if (clip.contrast != 0f) contrast(clip.contrast)
        if (clip.brightness != 0f) bright(clip.brightness)
        return cm
    }

    /** Efek warna berwaktu (matriks RGB 4×4 kolom-utama dari [VeFxMath]) → ColorMatrix Android. */
    fun fxMatrix(type: String, intensity: Float, p: Float, tlMs: Long): ColorMatrix? {
        val m = VeFxMath.colorMatrix(type, intensity, p, tlMs) ?: return null
        return ColorMatrix(floatArrayOf(
            m[0], m[4], m[8], 0f, m[12] * 255f,
            m[1], m[5], m[9], 0f, m[13] * 255f,
            m[2], m[6], m[10], 0f, m[14] * 255f,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    fun concat(a: ColorMatrix?, b: ColorMatrix?): ColorMatrix? = when {
        a == null -> b
        b == null -> a
        else -> ColorMatrix(a).also { it.postConcat(b) }
    }
}

internal object VeCover {
    /** Frame sumber (tegak) untuk klip pada waktu lokal timeline [localMs]; null bila gagal. */
    fun sourceFrame(ctx: Context, clip: VeClip, localMs: Long): Bitmap? {
        if (clip.isImage) return runCatching { VeAssets.decodeFit(ctx, clip.uri, 2048) }.getOrNull()
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, clip.uri)
            val srcMs = clip.trimStartMs + (localMs * clip.effectiveSpeed()).toLong()
            r.getFrameAtTime(srcMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(srcMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Gambar sampul pada waktu timeline [atMs] dengan rasio/fit/rotasi/zoom/filter/overlay seperti hasil render. */
    fun render(
        ctx: Context, atMs: Long, outW: Int, outH: Int,
        clips: List<VeClip> = VeProject.clips.toList(),
        overlays: List<VeOverlay> = VeProject.overlays.toList(),
        fitMode: String = VeProject.fitMode,
        showText: Boolean = VeProject.showText
    ): Bitmap? {
        var t = 0L
        var clip: VeClip? = null
        var local = 0L
        for (c in clips) {
            val d = c.timelineMs()
            if (atMs < t + d || c === clips.last()) { clip = c; local = (atMs - t).coerceIn(0L, max(0L, d - 1)); break }
            t += d
        }
        val c = clip ?: return null
        val frame = sourceFrame(ctx, c, local) ?: return null
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.BLACK)
        val rot = ((c.rotation % 360) + 360) % 360
        val odd = rot % 180 != 0
        val bw = if (odd) frame.height else frame.width
        val bh = if (odd) frame.width else frame.height
        val crop = fitMode == "isi"
        val scale = if (crop) max(outW / bw.toFloat(), outH / bh.toFloat()) else min(outW / bw.toFloat(), outH / bh.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        VePreviewFx.clipMatrix(c)?.let { paint.colorFilter = ColorMatrixColorFilter(it) }
        cv.save()
        val z = c.zoom.coerceIn(1f, 4f)
        cv.translate(outW / 2f + c.panX * (z - 1f) / 2f * outW, outH / 2f + c.panY * (z - 1f) / 2f * outH)
        cv.scale(if (c.flipH) -z else z, z)
        cv.rotate(rot.toFloat())
        cv.scale(scale, scale)
        cv.drawBitmap(frame, -frame.width / 2f, -frame.height / 2f, paint)
        cv.restore()
        if (showText) {
            for (o in overlays) {
                val st = VeAnimMath.state(o.animIn, o.animOut, o.animMs, o.startMs, o.durationMs, atMs)
                if (!st.visible) continue
                VeOverlayRender.draw(ctx, cv, o, outW, outH, (o.cx + st.dx) * outW, (o.cy + st.dy) * outH, st.alpha, st.scale, o.rotation, imageMaxSide = max(outW, outH))
            }
        }
        frame.recycle()
        return out
    }

    /** Ukuran sampul = ukuran render pada resolusi proyek. */
    fun size(): Pair<Int, Int> = veOutputSize(VeProject.aspect, VeProject.resolution, VeProject.clips)
}
