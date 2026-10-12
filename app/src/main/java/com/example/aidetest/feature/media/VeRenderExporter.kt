package com.example.aidetest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.Contrast
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.GaussianFunction
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SeparableConvolution
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pengaturan render penuh (encoding ulang). */
internal data class VeRenderSettings(
    val width: Int,
    val height: Int,
    /** pas | isi | blur */
    val fitMode: String,
    val includeAudio: Boolean,
    val showText: Boolean,
    val overlays: List<VeOverlay>,
    val effects: List<VeEffect>,
    val audios: List<VeAudioItem>,
    /** 0 = ikut sumber; >0 = turunkan frame rate. */
    val frameRate: Int = 0,
    /** 0 = bitrate dipilih encoder; >0 = bitrate video (bit/detik) yang diminta. */
    val videoBitrate: Int = 0
)

/** Ukuran tampil klip setelah rotasi bawaan + rotasi pengguna; null bila ukuran sumber belum diketahui. */
internal fun veClipDisplaySize(clip: VeClip): Pair<Int, Int>? {
    var w = clip.videoWidth
    var h = clip.videoHeight
    if (w <= 0 || h <= 0) return null
    if (clip.sourceRotation % 180 != 0) { val t = w; w = h; h = t }
    if (clip.rotation % 180 != 0) { val t = w; w = h; h = t }
    return w to h
}

/** Hitung resolusi output (genap) dari rasio + sisi pendek. "asli" memakai rasio klip video pertama. */
internal fun veOutputSize(aspect: String, shortSide: Int, clips: List<VeClip>): Pair<Int, Int> {
    val ratio: Float = when (aspect) {
        "16:9" -> 16f / 9f
        "9:16" -> 9f / 16f
        "1:1" -> 1f
        "4:5" -> 4f / 5f
        "4:3" -> 4f / 3f
        "3:4" -> 3f / 4f
        else -> {
            val first = clips.firstNotNullOfOrNull { veClipDisplaySize(it) }
            if (first == null) 16f / 9f else first.first.toFloat() / first.second
        }
    }
    fun even(v: Float): Int = (v.toInt() / 2 * 2).coerceAtLeast(2)
    return if (ratio >= 1f) even(shortSide * ratio) to even(shortSide.toFloat())
    else even(shortSide.toFloat()) to even(shortSide / ratio)
}

/**
 * Bitrate video untuk preset kualitas (bit/detik), berdasarkan piksel × fps × bit-per-piksel.
 * "auto" = 0 (encoder perangkat yang memilih); "auto-estimate" hanya untuk perkiraan ukuran di UI.
 */
internal fun veVideoBitrate(width: Int, height: Int, frameRate: Int, quality: String): Int {
    val bpp = when (quality) {
        "hemat" -> 0.06
        "tinggi" -> 0.14
        "auto-estimate" -> 0.1
        else -> return 0
    }
    val fps = if (frameRate > 0) frameRate else 30
    return (width.toDouble() * height * fps * bpp).toInt().coerceIn(500_000, 40_000_000)
}

/**
 * Mesin render penuh Video Editor berbasis Media3 Transformer.
 * Semua efek dirender ke frame (bukan metadata), lalu dienkode ulang ke MP4 H.264 + AAC.
 *
 * Teks/stiker/gambar (overlay) dan efek berwaktu diterapkan per klip (efek tingkat Composition di
 * Media3 1.4 hanya mendukung Presentation); tiap efek menormalkan waktunya dari frame pertama klip
 * dan memetakannya ke waktu timeline global, sehingga item yang melintasi beberapa klip tetap benar.
 * Audio tambahan (musik/suara/ekstrak) dicampur lebih dulu menjadi satu WAV stereo.
 *
 * Bila perangkat gagal merender (mis. efek GL tidak didukung), render diulang otomatis dengan
 * fitur lebih sederhana (level 1: tanpa blur/efek berwaktu; level 2: dasar saja) dan pengguna diberi tahu.
 *
 * Harus dimulai dari thread utama (Transformer memakai Looper pemanggil).
 */
@OptIn(markerClass = [UnstableApi::class])
internal class VeRenderExporter(
    private val context: Context,
    private val clips: List<VeClip>,
    private val settings: VeRenderSettings,
    private val onProgress: (Int) -> Unit,
    private val onDone: (Result<File>) -> Unit,
    private val onNotice: (String) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var finished = false
    private var cancelled = false
    private var level = 0
    private var out: File? = null
    private var premix: File? = null
    private val bitmaps = ArrayList<Bitmap>()

    private val poller = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (finished) return
            val holder = ProgressHolder()
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
            handler.postDelayed(this, 300L)
        }
    }

    fun start() {
        val totalMs = clips.sumOf { it.timelineMs() }
        val audios = settings.audios
        if (audios.isEmpty() || totalMs <= 0L) { launch(); return }
        // Campur audio tambahan di thread latar, lalu mulai Transformer di thread utama.
        onProgress(-1)
        val dir = context.cacheDir
        Thread {
            val wav = File(dir, "ve_mix_${System.currentTimeMillis()}.wav")
            val ok = runCatching {
                VeAudioMix.mixToWav(context, audios, totalMs, wav, cancelled = { cancelled }, onProgress = { })
            }.getOrDefault(false)
            handler.post {
                if (finished || cancelled) { wav.delete(); return@post }
                if (ok) premix = wav else {
                    wav.delete()
                    onNotice("Audio tambahan tidak bisa dibaca — ekspor tanpa audio tambahan")
                }
                launch()
            }
        }.start()
    }

    private fun launch() {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "ve_render_${System.currentTimeMillis()}.mp4")
        out = file
        val composition = try {
            buildComposition()
        } catch (e: Exception) {
            finish(Result.failure(e))
            return
        }
        val builder = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
        if (settings.videoBitrate > 0) {
            // Kualitas dipilih pengguna (Hemat/Tinggi): minta bitrate eksplisit ke encoder.
            builder.setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(settings.videoBitrate).build())
                    .build()
            )
        }
        val t = builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (file.exists() && file.length() > 0L) finish(Result.success(file))
                    else finish(Result.failure(IllegalStateException("Hasil render kosong")))
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    file.delete()
                    if (!cancelled && !finished && level < 2) {
                        level++
                        onNotice(
                            if (level == 1) "Perangkat gagal merender efek lanjutan — mengulang tanpa blur & efek berwaktu"
                            else "Mengulang dengan pengaturan dasar (tanpa overlay & efek)"
                        )
                        transformer = null
                        handler.removeCallbacks(poller)
                        handler.post { if (!finished && !cancelled) launch() }
                        return
                    }
                    finish(Result.failure(IllegalStateException(exportException.message ?: "Render gagal", exportException)))
                }
            })
            .build()
        transformer = t
        try {
            t.start(composition, file.absolutePath)
        } catch (e: Exception) {
            // Mis. format gambar tak didukung terdeteksi sinkron saat start: ikut jalur ulang bertahap.
            file.delete()
            if (!cancelled && !finished && level < 2) {
                level++
                onNotice(if (level == 1) "Perangkat gagal merender efek lanjutan — mengulang tanpa blur & efek berwaktu" else "Mengulang dengan pengaturan dasar (tanpa overlay & efek)")
                transformer = null
                handler.post { if (!finished && !cancelled) launch() }
            } else finish(Result.failure(e))
            return
        }
        handler.removeCallbacks(poller)
        handler.postDelayed(poller, 300L)
    }

    fun cancel() {
        if (finished) return
        cancelled = true
        runCatching { transformer?.cancel() }
        finish(Result.failure(IllegalStateException("Render dibatalkan")))
    }

    private fun finish(result: Result<File>) {
        if (finished) return
        finished = true
        handler.removeCallbacks(poller)
        // Bitmap overlay tidak di-recycle manual: thread GL Transformer bisa masih memakainya
        // sesaat setelah batal; biarkan GC yang membersihkan.
        bitmaps.clear()
        premix?.let { runCatching { it.delete() } }
        premix = null
        if (result.isFailure) out?.let { runCatching { it.delete() } }
        onDone(result)
    }

    // ------------------------------------------------------------------ composition

    private fun buildComposition(): Composition {
        if (clips.isEmpty()) error("Timeline kosong")
        bitmaps.clear()
        val items = ArrayList<EditedMediaItem>()
        var g0 = 0L
        for ((index, clip) in clips.withIndex()) {
            val next = clips.getOrNull(index + 1)
            items.add(buildItem(clip, index, next, g0))
            g0 += clip.timelineMs()
        }
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence(items))
        val mix = premix
        if (mix != null) {
            val mixItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(mix)))
                .setRemoveVideo(true)
                .build()
            // Berulang hanya sebagai pengaman: WAV sudah sepanjang video; berakhir bersama sequence video.
            sequences.add(EditedMediaItemSequence(ImmutableList.of(mixItem), true))
        }
        val needAudio = settings.includeAudio || mix != null
        return Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(needAudio)
            .build()
    }

    private fun buildItem(clip: VeClip, index: Int, next: VeClip?, g0Ms: Long): EditedMediaItem {
        val mediaBuilder = MediaItem.Builder().setUri(clip.uri)
        if (clip.isImage) {
            mediaBuilder.setMimeType(imageMime(clip.uri))
        } else {
            mediaBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.effectiveEnd())
                    .build()
            )
        }
        val video = ArrayList<Effect>()
        val audio = ArrayList<AudioProcessor>()
        val durationMs = clip.timelineMs()
        val g1Ms = g0Ms + durationMs

        val rot = ((clip.rotation % 360) + 360) % 360
        if (rot != 0) {
            // Media3: derajat positif = berlawanan jarum jam; rotasi UI = searah jarum jam.
            video.add(ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - rot).toFloat()).build())
        }
        val speed = clip.effectiveSpeed()
        if (!clip.isImage && speed != 1f) {
            video.add(SpeedChangeEffect(speed))
            audio.add(SonicAudioProcessor().apply { setSpeed(speed) })
        }
        video.addAll(colorEffects(clip))

        // Latar blur: bingkai sumber diperbesar ke rasio output (blur) dengan salinan tajam di tengah.
        var fit = settings.fitMode
        if (fit == "blur") {
            val dims = if (level == 0) veClipDisplaySize(clip) else null
            val blur = dims?.let { blurBackground(it.first, it.second) }
            if (blur != null) video.add(blur) else fit = "pas"
        }
        video.add(
            Presentation.createForWidthAndHeight(
                settings.width,
                settings.height,
                if (fit == "isi") Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
            )
        )
        if (level < 2 && clip.hasTransform()) video.add(clipTransform(clip))

        val durationUs = durationMs * 1000L
        if (level < 2) {
            val fadeIn = index > 0 && clip.transition == "fade"
            val fadeOut = next != null && next.transition == "fade"
            if (fadeIn || fadeOut) video.add(VeFadeMatrix(durationUs, fadeIn, fadeOut, VeTransMath.durationUs("fade", durationUs)))
            if (index > 0 && clip.transition != "none" && clip.transition != "fade") {
                val dur = VeTransMath.durationUs(clip.transition, durationUs)
                if (dur > 0L) video.add(VeMotionFx { tUs -> VeTransMath.motion(clip.transition, tUs.toFloat() / dur) })
            }
        }
        if (level == 0) {
            for (e in settings.effects) {
                if (e.endMs() <= g0Ms || e.startMs >= g1Ms) continue
                effectFor(e, g0Ms)?.let { video.add(it) }
            }
        }
        if (level < 2 && settings.showText) {
            val inClip = settings.overlays.filter { it.endMs() > g0Ms && it.startMs < g1Ms }
            if (inClip.isNotEmpty()) {
                // Item yang tidak bertumpuk berbagi satu slot; Media3 membatasi 15 overlay per efek.
                val lanes = VeOverlaySlots.assign(LongArray(inClip.size) { inClip[it].startMs }, LongArray(inClip.size) { inClip[it].endMs() })
                val slots = ArrayList<androidx.media3.effect.TextureOverlay>()
                for (l in 0..(lanes.maxOrNull() ?: 0)) {
                    val members = inClip.filterIndexed { i, _ -> lanes[i] == l }
                    if (members.isNotEmpty()) slots.add(VeSlotOverlay(context, members, g0Ms, settings.width, settings.height))
                }
                for (chunk in slots.chunked(15)) video.add(OverlayEffect(ImmutableList.copyOf(chunk)))
            }
        }
        if (settings.frameRate > 0 && !clip.isImage) video.add(FrameDropEffect.createDefaultFrameDropEffect(settings.frameRate.toFloat()))

        if (!clip.isImage && clip.volume != 1f && clip.volume > 0.001f) audio.addAll(volumeProcessors(clip.volume))

        val builder = EditedMediaItem.Builder(mediaBuilder.build())
            .setEffects(Effects(audio, video))
        if (clip.isImage) {
            @Suppress("DEPRECATION")
            builder.setDurationUs(durationUs).setFrameRate(if (settings.frameRate > 0) settings.frameRate else 30)
        } else if (!settings.includeAudio || clip.volume <= 0.001f) {
            builder.setRemoveAudio(true)
        }
        return builder.build()
    }

    /** Efek latar blur untuk sumber w0×h0 → rasio output; null bila rasio sudah sama (tanpa bar). */
    private fun blurBackground(w0: Int, h0: Int): Effect? {
        val s = w0.toFloat() / h0
        val t = settings.width.toFloat() / settings.height
        if (abs(s - t) < 0.01f) return null
        val sx: Float
        val sy: Float
        if (s > t) { sx = 1f; sy = t / s } else { sx = s / t; sy = 1f }
        val sigma = (0.012f * max(w0, h0)).coerceIn(6f, 40f)
        return GaussianBlurWithFrameOverlaid(sigma, sx, sy)
    }

    private fun clipTransform(clip: VeClip): Effect {
        // Geser: +panX menggeser isi ke kanan, +panY ke bawah (NDC: y ke atas) dalam rentang aman (zoom-1).
        val m = VeFxCompose.clipTransform(clip.zoom, clip.flipH, clip.panX, clip.panY)
        return VeMotionFx { m }
    }

    private fun effectFor(e: VeEffect, g0Ms: Long): Effect? {
        val dur = max(1L, e.durationMs)
        return when {
            VeFxMath.isMotion(e.type) -> VeMotionFx(g0Ms) { gMs ->
                val local = gMs - e.startMs
                if (local < 0 || local >= dur) floatArrayOf(1f, 1f, 0f, 0f)
                else VeFxMath.motion(e.type, e.intensity, local.toFloat() / dur, local)
            }
            VeFxMath.isColor(e.type) -> VeRgbFx(g0Ms) { gMs ->
                val local = gMs - e.startMs
                if (local < 0 || local >= dur) null
                else VeFxMath.colorMatrix(e.type, e.intensity, local.toFloat() / dur, local)
            }
            VeFxMath.isBlur(e.type) -> {
                val scale = min(settings.width, settings.height) / 720f
                VeBlurFx(g0Ms) { gMs ->
                    val local = gMs - e.startMs
                    if (local < 0 || local >= dur) 0.3f else VeFxMath.blurSigma(e.intensity, local.toFloat() / dur) * max(0.5f, scale)
                }
            }
            else -> null
        }
    }

    /** MIME yang pasti diterima Media3 (JPEG/PNG/WebP/BMP); isi sebenarnya didekode oleh BitmapFactory. */
    private fun imageMime(uri: Uri): String {
        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase(Locale.US)
        val path = uri.toString().lowercase(Locale.US)
        return when {
            fromResolver == "image/png" || (fromResolver == null && path.endsWith(".png")) -> "image/png"
            fromResolver == "image/webp" || (fromResolver == null && path.endsWith(".webp")) -> "image/webp"
            fromResolver == "image/bmp" || (fromResolver == null && path.endsWith(".bmp")) -> "image/bmp"
            else -> "image/jpeg"
        }
    }

    private fun volumeProcessors(volume: Float): List<AudioProcessor> {
        val v = volume.coerceIn(0f, 2f)
        val mixer = ChannelMixingAudioProcessor()
        mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(v))
        mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(v))
        return listOf(mixer)
    }

    private fun colorEffects(clip: VeClip): List<Effect> {
        val list = ArrayList<Effect>()
        when (clip.filter) {
            "bw" -> list.add(RgbFilter.createGrayscaleFilter())
            "invert" -> list.add(RgbFilter.createInvertedFilter())
            "warm" -> list.add(RgbAdjustment.Builder().setRedScale(1.10f).setGreenScale(1.02f).setBlueScale(0.86f).build())
            "cool" -> list.add(RgbAdjustment.Builder().setRedScale(0.88f).setGreenScale(1.0f).setBlueScale(1.12f).build())
            "vivid" -> {
                list.add(HslAdjustment.Builder().adjustSaturation(35f).build())
                list.add(Contrast(0.12f))
            }
            "fade" -> {
                list.add(HslAdjustment.Builder().adjustSaturation(-30f).build())
                list.add(Contrast(-0.2f))
                list.add(Brightness(0.06f))
            }
        }
        if (clip.temperature != 0f) {
            val t = clip.temperature.coerceIn(-1f, 1f)
            list.add(RgbAdjustment.Builder().setRedScale(1f + 0.14f * t).setGreenScale(1f + 0.02f * t).setBlueScale(1f - 0.18f * t).build())
        }
        if (clip.saturation != 0f) list.add(HslAdjustment.Builder().adjustSaturation(clip.saturation.coerceIn(-100f, 100f)).build())
        if (clip.contrast != 0f) list.add(Contrast(clip.contrast.coerceIn(-1f, 1f)))
        if (clip.brightness != 0f) list.add(Brightness(clip.brightness.coerceIn(-1f, 1f)))
        return list
    }
}

// ============================================================================ efek khusus

/**
 * Satu slot overlay: item-item yang waktunya tidak bertumpuk. Bitmap ketat dirender saat item aktif
 * (hanya satu disimpan); posisi, rotasi, skala, dan opasitas (termasuk animasi masuk/keluar) dihitung
 * per frame lewat OverlaySettings. Waktu dinormalkan dari frame pertama klip.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class VeSlotOverlay(
    private val context: Context,
    items: List<VeOverlay>,
    private val clipGlobalStartMs: Long,
    private val frameW: Int,
    private val frameH: Int
) : BitmapOverlay() {
    private val items = items.sortedBy { it.startMs }
    private val blank: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var base = Long.MIN_VALUE
    private var cachedId: String? = null
    private var cached: Bitmap? = null
    private var lastActive: VeOverlay? = null
    private var lastMs = Long.MIN_VALUE

    private fun globalMs(ptsUs: Long): Long {
        if (base == Long.MIN_VALUE) base = ptsUs
        return clipGlobalStartMs + (ptsUs - base) / 1000L
    }

    private fun activeAt(ms: Long): VeOverlay? {
        if (ms == lastMs) return lastActive
        var found: VeOverlay? = null
        for (v in items) {
            if (v.startMs > ms) break
            if (ms < v.endMs()) { found = v; break }
        }
        lastMs = ms
        lastActive = found
        return found
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val v = activeAt(globalMs(presentationTimeUs)) ?: return blank
        if (cachedId != v.id) {
            cached = runCatching { VeOverlayRender.renderTight(context, v, frameW, frameH) }.getOrNull()
            cachedId = v.id
        }
        return cached ?: blank
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = globalMs(presentationTimeUs)
        val v = activeAt(ms)
        getBitmap(presentationTimeUs) // pastikan bitmap item aktif sudah dirender sebelum ukuran/pengaturan dipakai
        if (v == null || cached == null) {
            return OverlaySettings.Builder().setAlphaScale(0f).build()
        }
        val st = VeAnimMath.state(v.animIn, v.animOut, v.animMs, v.startMs, v.durationMs, ms)
        val alpha = if (st.visible) (v.opacity * st.alpha).coerceIn(0f, 1f) else 0f
        // Pusat relatif bingkai (y ke bawah) → NDC (y ke atas); dx/dy animasi = pecahan lebar/tinggi.
        val ax = (v.cx * 2f - 1f + st.dx * 2f).coerceIn(-1f, 1f)
        val ay = (1f - v.cy * 2f - st.dy * 2f).coerceIn(-1f, 1f)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(ax, ay)
            .setScale(st.scale, st.scale)
            .setRotationDegrees(-v.rotation)
            .setAlphaScale(alpha)
            .build()
    }
}

/** Gerak (skala+geser NDC) dari fungsi; waktu dinormalkan dari frame pertama klip. */
@OptIn(markerClass = [UnstableApi::class])
internal class VeMotionFx private constructor(private val clipGlobalStartMs: Long, private val local: Boolean, private val f: (Long) -> FloatArray) : MatrixTransformation {
    /** Fungsi menerima waktu lokal klip (µs). */
    constructor(f: (Long) -> FloatArray) : this(0L, true, f)

    /** Fungsi menerima waktu timeline global (ms). */
    constructor(clipGlobalStartMs: Long, g: (Long) -> FloatArray) : this(clipGlobalStartMs, false, g)

    private var base = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (base == Long.MIN_VALUE) base = presentationTimeUs
        val rel = presentationTimeUs - base
        val m = if (local) f(rel) else f(clipGlobalStartMs + rel / 1000L)
        return Matrix().apply {
            postScale(m[0], m[1])
            postTranslate(m[2], m[3])
        }
    }
}

/** Matriks warna RGB 4×4 berwaktu (null = identitas). */
@OptIn(markerClass = [UnstableApi::class])
internal class VeRgbFx(private val clipGlobalStartMs: Long, private val g: (Long) -> FloatArray?) : RgbMatrix {
    private var base = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (base == Long.MIN_VALUE) base = presentationTimeUs
        val gMs = clipGlobalStartMs + (presentationTimeUs - base) / 1000L
        return g(gMs) ?: floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
}

/** Blur Gaussian dengan sigma berwaktu (sigma kecil di luar jendela efek = hampir tanpa efek). */
@OptIn(markerClass = [UnstableApi::class])
internal class VeBlurFx(private val clipGlobalStartMs: Long, private val sigmaAt: (Long) -> Float) : SeparableConvolution() {
    private var base = Long.MIN_VALUE

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D {
        if (base == Long.MIN_VALUE) base = presentationTimeUs
        val gMs = clipGlobalStartMs + (presentationTimeUs - base) / 1000L
        return GaussianFunction(max(0.3f, sigmaAt(gMs)), 2f)
    }
}

/**
 * Transisi "Pudar": skala RGB menuju hitam di awal/akhir klip (fade through black).
 * Waktu diukur dari frame pertama yang diterima efek ini, sehingga tidak bergantung pada
 * offset timestamp sequence.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class VeFadeMatrix(
    private val durationUs: Long,
    private val fadeIn: Boolean,
    private val fadeOut: Boolean,
    private val fadeUs: Long
) : RgbMatrix {
    private var startUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (startUs == Long.MIN_VALUE) startUs = presentationTimeUs
        val t = (presentationTimeUs - startUs).toFloat()
        val span = fadeUs.coerceAtLeast(1L).toFloat()
        var f = 1f
        if (fadeIn) f = min(f, t / span)
        if (fadeOut) f = min(f, (durationUs - t) / span)
        f = f.coerceIn(0f, 1f)
        return floatArrayOf(
            f, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, f, 0f,
            0f, 0f, 0f, 1f
        )
    }
}
