package com.example.aidetest

import android.content.Intent
import android.widget.LinearLayout
import java.io.File
import kotlin.math.max

/**
 * Alur ekspor Video Editor: panel pengaturan (resolusi, fps, kualitas, mode), render (Media3) atau
 * mode cepat (stream-copy), lalu menerbitkan hasil ke Galeri (Movies/GITLS) beserta sampul JPG opsional.
 * Render berjalan di latar dan tetap dipantau bila halaman dibangun ulang (state di [VeProject]).
 */
internal class VeExportUi(private val ui: VideoEditorUi) {
    private val a = ui.a
    private val kit = ui.kit
    private val p = ui.p
    private val handler = ui.handler
    private fun dp(v: Int): Int = ui.dp(v)

    init { current = this }

    /** Dipanggil saat editor ditutup: UI baru (mis. setelah Activity dibuat ulang) mengambil alih hasil ekspor. */
    fun detach() { if (current === this) current = null }

    companion object {
        /** Ekspor yang masih berjalan melapor ke UI terbaru, bukan Activity lama yang sudah dihancurkan. */
        @Volatile private var current: VeExportUi? = null
        @Volatile private var fastCancel = false
    }

    private fun alive(): Boolean = !a.isFinishing && !a.isDestroyed

    fun refreshButton() {
        val label = when {
            p.exporting && p.exportProgress >= 0 -> "Ekspor ${p.exportProgress}%"
            p.exporting -> "Ekspor…"
            else -> "Ekspor"
        }
        ui.bindExportViews(label, !p.exporting, if (p.exporting) p.exportProgress else 0, "${p.resolution}p ▾")
    }

    fun onExportClicked() {
        if (p.exporting) { showProgressDialog(); return }
        if (p.clips.isEmpty()) { a.toast("Timeline kosong — impor video atau foto dulu"); return }
        ui.playback.pause()
        if (p.exportMode != "render" && hasRenderOnlyFeatures()) {
            a.themedDialog()
                .setTitle("Mode Cepat tidak menerapkan semuanya")
                .setMessage("Mode Cepat hanya memangkas dan menggabung klip tanpa encoding ulang. Teks, stiker, efek, filter, kecepatan, transisi, dan audio tambahan TIDAK ikut. Pakai Render penuh untuk menyertakannya.")
                .setNegativeButton("Batal", null)
                .setNeutralButton("Render penuh") { _, _ -> p.exportMode = "render"; ui.save(); startExport() }
                .setPositiveButton("Tetap cepat") { _, _ -> startExport() }
                .show()
            return
        }
        startExport()
    }

    private fun hasRenderOnlyFeatures(): Boolean =
        p.overlays.isNotEmpty() || p.effects.isNotEmpty() || p.audios.isNotEmpty() ||
            p.clips.any { it.hasColorEdits() || it.speed != 1f || it.volume != 1f || it.transition != "none" || it.hasTransform() || it.isImage }

    // ------------------------------------------------------------------ panel pengaturan

    private fun outputInfo(): String {
        val (w, h) = veOutputSize(p.aspect, p.resolution, p.clips)
        val dur = p.totalMs()
        val fps = if (p.frameRate > 0) p.frameRate else 30
        val vb = veVideoBitrate(w, h, p.frameRate, if (p.quality == "auto") "auto-estimate" else p.quality)
        val bytes = ((vb + 128_000L) * (dur / 1000f) / 8f).toLong()
        return if (p.exportMode == "render") "${w}×${h} · ${fps}fps · ${VeTime.fmt(dur)} · perkiraan ${humanBytes(bytes)}"
        else "Ukuran & kualitas mengikuti sumber · ${VeTime.fmt(dur)}"
    }

    fun openPanel() {
        val body = kit.vbox()
        val info = kit.text(outputInfo(), 12.5f, kit.ink, true)
        body.addView(info)
        body.addView(kit.section("Mode"))
        body.addView(kit.chips(listOf("render" to "Render penuh", "cepat" to "Cepat (tanpa render ulang)"), p.exportMode) { key ->
            p.exportMode = key
            ui.save()
            refreshButton()
            openPanel()
        })
        if (p.exportMode == "render") {
            body.addView(kit.section("Resolusi"))
            body.addView(kit.chips(listOf("480" to "480p", "720" to "720p", "1080" to "1080p", "1440" to "1440p (2K)"), p.resolution.toString()) { key ->
                p.resolution = key.toInt()
                ui.save()
                ui.layoutMedia()
                refreshButton()
                openPanel()
            })
            body.addView(kit.section("Frame rate"))
            body.addView(kit.chips(listOf("0" to "Asli", "30" to "30 fps", "24" to "24 fps"), p.frameRate.toString()) { key ->
                p.frameRate = key.toInt()
                ui.save()
                openPanel()
            })
            body.addView(kit.section("Kualitas"))
            body.addView(kit.chips(listOf("auto" to "Otomatis", "hemat" to "Hemat ukuran", "tinggi" to "Kualitas tinggi"), p.quality) { key ->
                p.quality = key
                ui.save()
                openPanel()
            })
            body.addView(kit.note("Frame rate hanya bisa diturunkan dari sumber. Resolusi 1440p tidak didukung semua perangkat — bila gagal, turunkan ke 1080p."))
        } else {
            body.addView(kit.note("Mode Cepat menggabung klip video apa adanya (stream-copy): sangat cepat dan tanpa penurunan kualitas, tetapi tanpa teks, efek, filter, kecepatan, transisi, foto, atau audio tambahan. Pemotongan jatuh di keyframe terdekat sebelum titik potong, jadi awal klip bisa sedikit lebih awal; semua klip harus berformat sama."))
        }
        body.addView(kit.section("Opsi"))
        body.addView(kit.switchRow("Sertakan suara klip", p.includeAudio) { on -> p.includeAudio = on; ui.save(); ui.afterChange(reload = false) })
        if (p.exportMode == "render") {
            body.addView(kit.switchRow("Tampilkan teks, keterangan & stiker", p.showText) { on -> p.showText = on; ui.save(); ui.afterChange(reload = false) })
        }
        body.addView(kit.switchRow("Simpan sampul (JPG) bersama video", p.coverOn) { on -> p.coverOn = on; ui.save() })
        body.addView(kit.button("Mulai ekspor", "export", true) { ui.closePanel(); onExportClicked() },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        ui.openPanel("Pengaturan ekspor", body)
    }

    // ------------------------------------------------------------------ eksekusi

    private fun startExport() {
        if (p.exporting) return
        a.ensureLegacyStorageAccess() // OPEN-11: Android 7–9 → minta izin agar hasil masuk Galeri
        if (p.exportMode == "render") startRender() else startFast()
    }

    private fun startRender() {
        val clipsCopy = p.clips.map { it.copy() }
        val (ow, oh) = veOutputSize(p.aspect, p.resolution, clipsCopy)
        val settings = VeRenderSettings(
            width = ow,
            height = oh,
            fitMode = p.fitMode,
            includeAudio = p.includeAudio,
            showText = p.showText,
            overlays = p.overlays.map { it.copy() },
            effects = p.effects.map { it.copy() },
            audios = p.audios.map { it.copy() },
            frameRate = p.frameRate,
            videoBitrate = veVideoBitrate(ow, oh, p.frameRate, p.quality)
        )
        p.exporting = true
        p.exportProgress = 0
        refreshButton()
        a.toast("Mengekspor ${ow}×${oh}… jangan tutup aplikasi")
        val appContext = a.applicationContext
        val exporter = VeRenderExporter(
            appContext, clipsCopy, settings,
            onProgress = { pct ->
                p.exportProgress = pct.coerceAtMost(100)
                refreshIfAttached()
            },
            onDone = { result -> (current ?: this).finish(result) },
            onNotice = { msg -> if (alive()) a.toast(msg) }
        )
        VeProject.activeRender = exporter
        runCatching { exporter.start() }.onFailure { e -> finish(Result.failure(e)) }
    }

    private fun startFast() {
        val videoClips = p.clips.filter { !it.isImage }.map { it.copy() }
        if (videoClips.isEmpty()) { a.toast("Mode Cepat butuh minimal satu klip video. Pakai Render penuh untuk foto."); return }
        p.exporting = true
        p.exportProgress = -1
        fastCancel = false
        refreshButton()
        val audio = p.includeAudio
        Thread {
            val result = runCatching { a.exportVideoProject(videoClips, audio) { fastCancel } }
            handler.post { (current ?: this).finish(result) }
        }.start()
    }

    private fun refreshIfAttached() {
        if (ui.root.isAttachedToWindow) refreshButton()
    }

    private fun showProgressDialog() {
        if (!alive()) return
        val pct = p.exportProgress
        a.themedDialog()
            .setTitle("Sedang mengekspor")
            .setMessage(if (pct >= 0) "Kemajuan: $pct%\n\nBiarkan layar tetap menyala sampai selesai." else "Memproses…")
            .setNegativeButton("Tutup", null)
            .setPositiveButton("Batalkan ekspor") { _, _ ->
                val r = VeProject.activeRender
                if (r != null) r.cancel() else fastCancel = true
                a.toast("Ekspor dibatalkan")
            }
            .show()
    }

    private fun finish(result: Result<File>) {
        p.exporting = false
        p.exportProgress = -1
        VeProject.activeRender = null
        refreshIfAttached()
        if (!alive()) {
            result.onFailure { e -> android.util.Log.w("GITLS", "export failed: ${e.message}") }
            // Editor sudah ditutup: hasil yang selesai tetap diterbitkan ke galeri dan diberi tahu lewat toast.
            val f = result.getOrNull() ?: return
            val app = a.applicationContext
            Thread {
                val pub = runCatching { VePublish.video(app, f, p.name.ifBlank { "video" }) }.getOrNull()
                handler.post { android.widget.Toast.makeText(app, "Ekspor selesai: " + (pub?.label ?: f.name), android.widget.Toast.LENGTH_LONG).show() }
            }.start()
            return
        }
        val file = result.getOrNull()
        if (file == null) {
            val msg = result.exceptionOrNull()?.message ?: "Render gagal"
            if (msg.contains("dibatalkan")) return
            a.themedDialog()
                .setTitle("Ekspor gagal")
                .setMessage("$msg\n\nCoba turunkan resolusi/frame rate, ganti kualitas ke Otomatis, atau hapus efek yang rumit lalu ekspor lagi.")
                .setPositiveButton("Mengerti", null)
                .show()
            return
        }
        a.toast("Menyimpan hasil…")
        val coverOn = p.coverOn
        val coverMs = p.coverMs
        val baseName = p.name.ifBlank { "video" }
        val totalMs = p.totalMs()
        val (cw, ch) = VeCover.size()
        // Salinan data saat ini (di thread utama): sampul tidak membaca proyek yang sedang diedit dari thread latar.
        val coverClips = p.clips.map { it.copy() }
        val coverOverlays = p.overlays.map { it.copy() }
        val coverFit = p.fitMode
        val coverText = p.showText
        Thread {
            val pub = runCatching { VePublish.video(a, file, baseName) }.getOrElse {
                // Gagal menerbitkan ke penyimpanan publik: berkas tetap di folder aplikasi dan bisa dibagikan.
                VePublished(
                    androidx.core.content.FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", file),
                    "Folder aplikasi/Movies/${file.name}", file.length(), "video/mp4", file
                )
            }
            var coverLabel: String? = null
            if (coverOn) {
                val bmp = runCatching { VeCover.render(a, coverMs.coerceIn(0L, max(0L, totalMs - 1)), cw, ch, coverClips, coverOverlays, coverFit, coverText) }.getOrNull()
                if (bmp != null) coverLabel = runCatching { a.saveImageToGallery(bmp, baseName + "_sampul", "jpg", 92).label }.getOrNull()
            }
            handler.post {
                p.lastExportUri = pub.uri
                p.lastExportLabel = pub.label
                p.lastExport = pub.file
                if (!alive()) {
                    android.widget.Toast.makeText(a.applicationContext, "Ekspor selesai: ${pub.label}", android.widget.Toast.LENGTH_LONG).show()
                    return@post
                }
                showResultDialog(pub, coverLabel, totalMs, cw, ch)
            }
        }.start()
    }

    private fun showResultDialog(pub: VePublished, coverLabel: String?, totalMs: Long, w: Int, h: Int) {
        val msg = buildString {
            append(pub.label).append('\n')
            append(humanBytes(pub.bytes)).append(" · ").append(VeTime.fmt(totalMs)).append(" · ").append("${w}×${h}")
            if (p.exportMode != "render") append("\n(Mode Cepat — tanpa render ulang)")
            if (coverLabel != null) append("\nSampul: ").append(coverLabel)
        }
        a.themedDialog()
            .setTitle("Ekspor selesai")
            .setMessage(msg)
            .setNegativeButton("Tutup", null)
            .setNeutralButton("Putar") { _, _ ->
                runCatching {
                    a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(pub.uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure { a.toast("Tidak ada pemutar video: ${it.message}") }
            }
            .setPositiveButton("Bagikan") { _, _ ->
                runCatching {
                    a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, pub.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Bagikan video"))
                }.onFailure { a.toast("Gagal membagikan: ${it.message}") }
            }
            .show()
    }
}
