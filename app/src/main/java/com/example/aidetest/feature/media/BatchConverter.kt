package com.example.aidetest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converter & Batch Tools — antrean konversi lokal dengan validasi kemampuan.
 *
 * Image: JPEG / PNG / WebP (+ resize & quality)
 * Audio: ekstrak/salin ke M4A (AAC) bila track audio ada; WAV via dekode PCM
 * Video: inspeksi codec; ekspor MP4 stream-copy bila kompatibel (bukan re-encode penuh)
 *
 * Tidak menjanjikan semua kombinasi format — setiap item divalidasi saat impor.
 */

enum class BcKind { IMAGE, AUDIO, VIDEO, UNKNOWN }

enum class BcStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class BcJob(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val kind: BcKind,
    val mime: String,
    val detail: String,
    var target: String,
    var status: BcStatus = BcStatus.QUEUED,
    var progress: Int = 0,
    var message: String = "",
    var outputPath: String? = null,
    /** Image options */
    var maxSide: Int = 1920,
    var quality: Int = 85,
    /** Video/audio notes from probe */
    var videoCodec: String? = null,
    var audioCodec: String? = null,
    var hasAlpha: Boolean = false,
    var width: Int = 0,
    var height: Int = 0
)

@Volatile
internal var batchConverterPickPending: ((List<Uri>) -> Unit)? = null
private const val BC_PICK = 1260

internal fun MainActivity.batchConverterTool() {
    clearPage("Converter & Batch")
    title.text = "Converter & Batch"
    action.text = "?"
    action.textSize = 20f
    action.isClickable = true
    action.setOnClickListener {
        android.app.AlertDialog.Builder(this)
            .setTitle("Converter & Batch")
            .setMessage(
                "Antrean konversi lokal:\n" +
                    "• Gambar → JPEG / PNG / WebP (resize + kualitas)\n" +
                    "• Audio → WAV (PCM mono, maks 10 menit) atau M4A bila track AAC bisa disalin\n" +
                    "• Video → inspeksi codec; salin ke MP4 bila memungkinkan\n\n" +
                    "Format divalidasi dari codec/container, bukan hanya ekstensi.\n" +
                    "Re-encode video penuh & MP3 encode native tidak dijanjikan.\n" +
                    "Progress · retry · batalkan antrean."
            )
            .setPositiveButton("OK", null)
            .show()
    }
    val jobs = mutableListOf<BcJob>()
    val batchCancelFlag = AtomicBoolean(false)
    var batchRunning = false

    back.setOnClickListener {
        batchCancelFlag.set(true)
        navigateBack()
    }

    content.addView(subLabel(
        "Batch · validasi codec · progress · retry · batal",
        12f
    ).apply { setPadding(0, 0, 0, dp(8)) })

    var defaultImageTarget = "JPEG"
    var defaultMaxSide = 1920
    var defaultQuality = 85

    val summary = label("0 item dalam antrean", 13f, true)
    content.addView(summary)

    val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(listBox)

    val overallProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
    }
    content.addView(overallProgress.apply {
        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
    })
    val overallLabel = subLabel("Siap", 12f)
    content.addView(overallLabel)

    fun refreshList() {
        listBox.removeAllViews()
        jobs.forEach { job ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = bg(panel2, 12, line)
            }
            val icon = when (job.kind) {
                BcKind.IMAGE -> "🖼"
                BcKind.AUDIO -> "🎵"
                BcKind.VIDEO -> "🎬"
                else -> "📄"
            }
            row.addView(label("$icon ${job.name}", 13f, true))
            row.addView(subLabel(
                "${job.kind} · ${job.mime.ifBlank { "?" }} → ${job.target}\n${job.detail}",
                11f
            ).apply { setPadding(0, dp(2), 0, 0) })
            val st = when (job.status) {
                BcStatus.QUEUED -> "Antre"
                BcStatus.RUNNING -> "Berjalan ${job.progress}%"
                BcStatus.DONE -> "Selesai"
                BcStatus.FAILED -> "Gagal"
                BcStatus.CANCELLED -> "Dibatalkan"
            }
            row.addView(subLabel("$st · ${job.message}", 11f).apply { setPadding(0, dp(2), 0, 0) })
            if (job.outputPath != null) {
                row.addView(subLabel(job.outputPath!!, 10f).apply { setPadding(0, dp(2), 0, 0) })
            }
            val acts = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            if (job.kind == BcKind.IMAGE) {
                acts.addView(button("JPEG") { job.target = "JPEG"; refreshList() },
                    LinearLayout.LayoutParams(0, -2, 1f))
                acts.addView(button("PNG") { job.target = "PNG"; refreshList() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
                acts.addView(button("WebP") { job.target = "WEBP"; refreshList() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
            } else if (job.kind == BcKind.AUDIO) {
                acts.addView(button("WAV") { job.target = "WAV"; refreshList() },
                    LinearLayout.LayoutParams(0, -2, 1f))
                acts.addView(button("M4A") { job.target = "M4A"; refreshList() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
            } else if (job.kind == BcKind.VIDEO) {
                acts.addView(button("MP4 copy") { job.target = "MP4_COPY"; refreshList() },
                    LinearLayout.LayoutParams(0, -2, 1f))
                acts.addView(button("Audio M4A") { job.target = "AUDIO_M4A"; refreshList() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
            }
            acts.addView(button("Hapus") {
                jobs.removeAll { it.id == job.id }
                summary.text = "${jobs.size} item dalam antrean"
                refreshList()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
            if (job.status == BcStatus.FAILED || job.status == BcStatus.CANCELLED) {
                acts.addView(button("Retry") {
                    job.status = BcStatus.QUEUED
                    job.progress = 0
                    job.message = "Siap diulang"
                    job.outputPath = null
                    refreshList()
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
            }
            row.addView(acts)
            listBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        if (jobs.isEmpty()) {
            listBox.addView(subLabel("Belum ada file. Ketuk Tambah file.", 12f))
        }
        summary.text = "${jobs.size} item · " +
            "selesai ${jobs.count { it.status == BcStatus.DONE }} · " +
            "gagal ${jobs.count { it.status == BcStatus.FAILED }}"
    }

    // Global image options
    content.addView(label("Opsi gambar (batch)", 13f, true).apply { setPadding(0, dp(8), 0, dp(4)) })
    val imgTargetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    listOf("JPEG", "PNG", "WEBP").forEachIndexed { i, t ->
        imgTargetRow.addView(button(t) {
            defaultImageTarget = t
            jobs.filter { it.kind == BcKind.IMAGE && it.status == BcStatus.QUEUED }.forEach { it.target = t }
            refreshList()
            toast("Target gambar: $t")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (i > 0) leftMargin = dp(4)
        })
    }
    content.addView(imgTargetRow)

    val qLab = label("Kualitas JPEG/WebP: 85%", 12f).apply { setPadding(0, dp(6), 0, 0) }
    content.addView(qLab)
    content.addView(SeekBar(this).apply {
        max = 100
        progress = 85
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                defaultQuality = progress.coerceAtLeast(10)
                qLab.text = "Kualitas JPEG/WebP: $defaultQuality%"
                jobs.filter { it.kind == BcKind.IMAGE }.forEach { it.quality = defaultQuality }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    })
    val sideLab = label("Sisi terpanjang maks: 1920 px", 12f)
    content.addView(sideLab)
    content.addView(SeekBar(this).apply {
        max = 36 // 400..4000
        progress = 15 // ~1900
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                defaultMaxSide = (400 + progress * 100).coerceIn(400, 4000)
                sideLab.text = "Sisi terpanjang maks: $defaultMaxSide px"
                jobs.filter { it.kind == BcKind.IMAGE }.forEach { it.maxSide = defaultMaxSide }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    })

    content.addView(subLabel(
        "Video: MP4 stream-copy hanya menyalin bitstream (resolusi/bitrate sumber). " +
            "Ubah resolusi/bitrate butuh re-encode (belum di modul ini). " +
            "MP3 encode native tidak tersedia.",
        11f
    ).apply { setPadding(0, dp(8), 0, dp(6)) })

    fun probeAndEnqueue(uri: Uri) {
        persistUriPermissionsSafely(uri, wantWrite = false)
        val name = runCatching { displayNameOf(uri) }.getOrDefault(uri.lastPathSegment ?: "file")
        val mime = contentResolver.getType(uri).orEmpty()
        val job = probeMediaJob(uri, name, mime, defaultImageTarget, defaultMaxSide, defaultQuality)
        runOnUiThread {
            jobs.add(job)
            refreshList()
        }
    }

    content.addView(button("Tambah file (multi)") {
        batchConverterPickPending = { uris ->
            setToolState(overallLabel, Ds.State.LOADING, "Memeriksa ${uris.size} file…")
            Thread {
                uris.forEach { probeAndEnqueue(it) }
                runOnUiThread {
                    setToolState(overallLabel, Ds.State.INFO, "Siap · ${jobs.size} item")
                    refreshList()
                    toast("Ditambah ke antrean")
                }
            }.start()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf(
                    "image/*", "audio/*", "video/*"
                ))
            },
            BC_PICK
        )
    })

    val runRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, 0)
    }
    // Satu jalur eksekusi antrean untuk tombol di halaman dan tombol dock (sebelumnya dua salinan kode).
    fun runQueue() {
        if (batchRunning) { toast("Sedang berjalan"); return }
        if (jobs.none { it.status == BcStatus.QUEUED || it.status == BcStatus.FAILED }) {
            toast("Tidak ada pekerjaan siap")
            return
        }
        // reset failed to queued if user hit run
        jobs.filter { it.status == BcStatus.FAILED }.forEach {
            it.status = BcStatus.QUEUED
            it.message = "Siap"
        }
        batchCancelFlag.set(false)
        ensureLegacyStorageAccess() // OPEN-11: Android 7–9 → izin agar hasil masuk Galeri
        batchRunning = true
        setToolState(overallLabel, Ds.State.LOADING, "Memproses…")
        Thread {
            val pending = jobs.filter { it.status == BcStatus.QUEUED }
            pending.forEachIndexed { index, job ->
                if (batchCancelFlag.get()) {
                    job.status = BcStatus.CANCELLED
                    job.message = "Dibatalkan"
                    runOnUiThread { refreshList() }
                    return@forEachIndexed
                }
                job.status = BcStatus.RUNNING
                job.progress = 0
                job.message = "Memproses…"
                runOnUiThread {
                    refreshList()
                    overallProgress.progress = (index * 100) / pending.size.coerceAtLeast(1)
                    setToolState(overallLabel, Ds.State.LOADING, "Item ${index + 1}/${pending.size}")
                }
                try {
                    val out = processBatchJob(job, { p ->
                        job.progress = p
                        runOnUiThread { refreshList() }
                    }) { batchCancelFlag.get() }
                    // Hasil kosong = dibatalkan sebelum terbit. Hasil yang sudah terbit tetap "OK"
                    // agar status sesuai dengan file yang memang ada di galeri.
                    if (out.isEmpty()) {
                        job.status = BcStatus.CANCELLED
                        job.message = "Dibatalkan"
                    } else {
                        job.status = BcStatus.DONE
                        job.progress = 100
                        job.outputPath = out
                        job.message = "OK"
                    }
                } catch (e: Exception) {
                    job.status = BcStatus.FAILED
                    job.message = e.message ?: "Gagal"
                }
                runOnUiThread { refreshList() }
            }
            batchRunning = false
            val wasCancelled = batchCancelFlag.get()
            runOnUiThread {
                overallProgress.progress = 100
                if (wasCancelled) setToolState(overallLabel, Ds.State.WARNING, "Dibatalkan") else setToolState(overallLabel, Ds.State.SUCCESS, "Selesai antrean")
                toast(if (wasCancelled) "Antrean dibatalkan; hasil yang belum selesai tidak disimpan" else "Antrean selesai")
            }
        }.start()
    }
    runRow.addView(button("Jalankan antrean") { runQueue() }, LinearLayout.LayoutParams(0, -2, 1f))
    runRow.addView(button("Batalkan") {
        batchCancelFlag.set(true)
        setToolState(overallLabel, Ds.State.LOADING, "Membatalkan…")
        toast("Pembatalan diminta")
    }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
    content.addView(runRow)

    pinToolAction(button("Jalankan antrean") { runQueue() })

    refreshList()
}

internal fun MainActivity.probeMediaJob(
    uri: Uri,
    name: String,
    mime: String,
    defaultImageTarget: String,
    maxSide: Int,
    quality: Int
): BcJob {
    val lower = name.lowercase(Locale.US)
    val m = mime.lowercase(Locale.US)

    // Image
    if (m.startsWith("image/") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp") ||
        lower.endsWith(".gif") || lower.endsWith(".heic")
    ) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val hasAlpha = m.contains("png") || m.contains("webp") || lower.endsWith(".png") || lower.endsWith(".webp")
        val detail = buildString {
            append("${opts.outWidth}×${opts.outHeight}")
            if (opts.outMimeType != null) append(" · ${opts.outMimeType}")
            if (hasAlpha) append(" · alpha mungkin ada")
            if (opts.outWidth <= 0) append(" · ukuran tidak terbaca")
        }
        val target = when {
            defaultImageTarget == "PNG" -> "PNG"
            defaultImageTarget == "WEBP" -> "WEBP"
            else -> "JPEG"
        }
        return BcJob(
            uri = uri, name = name, kind = BcKind.IMAGE, mime = mime.ifBlank { opts.outMimeType ?: "image/*" },
            detail = detail, target = target, maxSide = maxSide, quality = quality,
            hasAlpha = hasAlpha, width = opts.outWidth, height = opts.outHeight
        )
    }

    // Probe with extractor for audio/video
    val extractor = MediaExtractor()
    try {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
        } ?: extractor.setDataSource(this, uri, null)
    } catch (e: Exception) {
        return BcJob(
            uri = uri, name = name, kind = BcKind.UNKNOWN, mime = mime,
            detail = "Tidak bisa dibaca: ${e.message}", target = "—"
        ).also { it.status = BcStatus.FAILED; it.message = "Import gagal" }
    }

    var videoCodec: String? = null
    var audioCodec: String? = null
    var width = 0
    var height = 0
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val trackMime = f.getString(MediaFormat.KEY_MIME) ?: continue
        when {
            trackMime.startsWith("video/") && videoCodec == null -> {
                videoCodec = trackMime
                width = try { f.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Exception) { 0 }
                height = try { f.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Exception) { 0 }
            }
            trackMime.startsWith("audio/") && audioCodec == null -> {
                audioCodec = trackMime
            }
        }
    }
    extractor.release()

    val duration = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    } catch (_: Exception) { 0L }

    return when {
        videoCodec != null || m.startsWith("video/") -> {
            val detail = buildString {
                append("video=${videoCodec ?: "?"} · audio=${audioCodec ?: "—"}")
                if (width > 0) append(" · ${width}×${height}")
                if (duration > 0) append(" · ${duration / 1000}s")
                append(" · ekspor: salin MP4 atau ekstrak audio (bukan re-encode)")
            }
            BcJob(
                uri = uri, name = name, kind = BcKind.VIDEO, mime = mime.ifBlank { videoCodec ?: "video/*" },
                detail = detail, target = "MP4_COPY",
                videoCodec = videoCodec, audioCodec = audioCodec, width = width, height = height
            )
        }
        audioCodec != null || m.startsWith("audio/") -> {
            val canM4a = audioCodec?.contains("mp4a") == true || audioCodec?.contains("aac") == true ||
                lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".mp4")
            val detail = buildString {
                append("codec=${audioCodec ?: "?"}")
                if (duration > 0) append(" · ${duration / 1000}s")
                append(if (canM4a) " · M4A copy mungkin OK" else " · saran: WAV (dekode PCM)")
                append(" · MP3 encode native: tidak tersedia")
            }
            BcJob(
                uri = uri, name = name, kind = BcKind.AUDIO, mime = mime.ifBlank { audioCodec ?: "audio/*" },
                detail = detail, target = if (canM4a) "M4A" else "WAV",
                audioCodec = audioCodec
            )
        }
        else -> BcJob(
            uri = uri, name = name, kind = BcKind.UNKNOWN, mime = mime,
            detail = "Bukan image/audio/video yang dikenali perangkat ini", target = "—"
        ).also { it.status = BcStatus.FAILED; it.message = "Tidak didukung" }
    }
}

/**
 * Jalankan satu pekerjaan lalu salin hasilnya ke penyimpanan publik (Pictures/Music/Movies → GITLS, Android 10+)
 * agar muncul di galeri/aplikasi musik. Mengembalikan lokasi yang ditampilkan ke pengguna.
 */
internal fun MainActivity.processBatchJob(job: BcJob, onProgress: (Int) -> Unit, isCancelled: () -> Boolean = { false }): String {
    val path = processBatchJobRaw(job, onProgress)
    // OPEN-07: batal di tengah → hasil sementara dihapus dan TIDAK diterbitkan ke galeri.
    if (isCancelled()) {
        runCatching { java.io.File(path).delete() }
        return ""
    }
    val published = runCatching { VePublish.copy(this, java.io.File(path), deleteSource = true) }.getOrNull()
    return published?.label ?: path
}

internal fun MainActivity.processBatchJobRaw(job: BcJob, onProgress: (Int) -> Unit): String {
    onProgress(5)
    return when (job.kind) {
        BcKind.IMAGE -> {
            onProgress(15)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(job.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (longest / sample > job.maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = contentResolver.openInputStream(job.uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: error("Gagal dekode gambar")
            bmp = VeAssets.applyExif(this, job.uri, bmp)
            onProgress(40)
            val side = maxOf(bmp.width, bmp.height)
            if (side > job.maxSide) {
                val scale = job.maxSide.toFloat() / side
                val nw = (bmp.width * scale).toInt().coerceAtLeast(1)
                val nh = (bmp.height * scale).toInt().coerceAtLeast(1)
                bmp = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            }
            onProgress(70)
            // Flatten alpha for JPEG
            if (job.target == "JPEG" && bmp.hasAlpha()) {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val c = Canvas(flat)
                c.drawColor(Color.WHITE)
                c.drawBitmap(bmp, 0f, 0f, null)
                bmp = flat
            }
            val format = when (job.target) {
                "PNG" -> Bitmap.CompressFormat.PNG
                "WEBP" -> Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            val q = if (job.target == "PNG") 100 else job.quality
            val file = saveBitmapToAppImages(bmp, "batch_${job.name.substringBeforeLast('.')}", format, q)
            onProgress(100)
            file.absolutePath
        }
        BcKind.AUDIO -> {
            onProgress(20)
            when (job.target) {
                "M4A" -> {
                    val file = extractAudioToM4a(job.uri, "batch_audio")
                    onProgress(100)
                    file.absolutePath
                }
                else -> {
                    val decoded = decodeUriToPcm(job.uri, failIfTooLong = true)
                    onProgress(70)
                    val file = writeWavFile(decoded.first, decoded.second, "batch_audio")
                    onProgress(100)
                    file.absolutePath
                }
            }
        }
        BcKind.VIDEO -> {
            onProgress(15)
            when (job.target) {
                "AUDIO_M4A" -> {
                    val file = extractAudioToM4a(job.uri, "batch_from_video")
                    onProgress(100)
                    file.absolutePath
                }
                else -> {
                    // Stream-copy entire file tracks into mp4 container when possible
                    val file = copyMediaToMp4(job.uri, "batch_video")
                    onProgress(100)
                    file.absolutePath
                }
            }
        }
        BcKind.UNKNOWN -> error("Jenis file tidak didukung")
    }
}

/** Salin track video+audio ke kontainer MP4 (stream-copy). Gagal jika codec tidak diterima muxer. */
internal fun MainActivity.copyMediaToMp4(uri: Uri, baseName: String): File {
    val extractor = MediaExtractor()
    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        extractor.setDataSource(pfd.fileDescriptor)
    } ?: extractor.setDataSource(this, uri, null)

    val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
    if (!dir.exists()) dir.mkdirs()
    val safe = baseName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").take(40)
    val out = File(dir, "${safe}_${System.currentTimeMillis()}.mp4")
    val muxer = android.media.MediaMuxer(out.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    data class MapTrack(val index: Int, val outIndex: Int)
    val maps = mutableListOf<MapTrack>()
    for (i in 0 until extractor.trackCount) {
        val f = extractor.getTrackFormat(i)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            try {
                val outIndex = muxer.addTrack(f)
                maps.add(MapTrack(i, outIndex))
            } catch (e: Exception) {
                extractor.release()
                runCatching { muxer.release() }
                out.delete()
                throw IllegalStateException("Codec tidak bisa di-mux ke MP4 ($mime): ${e.message}")
            }
        }
    }
    if (maps.isEmpty()) {
        extractor.release()
        runCatching { muxer.release() }
        out.delete()
        error("Tidak ada track video/audio")
    }
    muxer.start()
    val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
    val info = android.media.MediaCodec.BufferInfo()
    try {
        for (m in maps) {
            extractor.selectTrack(m.index)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.bufferFlags()
                muxer.writeSampleData(m.outIndex, buffer, info)
                extractor.advance()
            }
            extractor.unselectTrack(m.index)
        }
    } finally {
        try { muxer.stop() } catch (_: Exception) {}
        muxer.release()
        extractor.release()
    }
    if (!out.exists() || out.length() < 500L) {
        out.delete()
        error("Hasil MP4 kosong — codec mungkin tidak kompatibel")
    }
    return out
}
