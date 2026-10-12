package com.example.aidetest

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mesin pratinjau Video Editor:
 *  - klip utama (MediaPlayer + TextureView, atau gambar) mengikuti playhead timeline,
 *  - klip berikutnya disiapkan lebih awal; potongan berurutan dari sumber yang sama diputar tanpa jeda,
 *  - item audio tambahan (musik, suara, ekstrak) diputar bersamaan dengan fade & volume,
 *  - seek saat scrub digabung (satu seek berjalan, sisanya diambil yang terbaru),
 *  - waktu playhead dihaluskan per frame layar (Choreographer).
 * Hanya dipakai dari thread utama.
 */
internal class VePlayback(
    private val a: MainActivity,
    private val texture: TextureView,
    private val image: ImageView,
    private val listener: Listener
) {
    interface Listener {
        /** Playhead maju oleh pemutaran (p.playheadMs sudah diperbarui). */
        fun onTime(ms: Long)
        fun onPlayState(playing: Boolean)
        /** Klip dimuat / ukuran video diketahui → tata ulang bingkai pratinjau. */
        fun onMediaChanged()
        fun onError(message: String)
    }

    private val p = VeProject
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()

    var playing = false
        private set
    private var released = false
    var loadedClipId: String? = null
        private set
    private var loadedUri: Uri? = null
    private var surface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingTargetMs = -1L
    private var seeking = false
    private var pendingSeek = -1L
    private val seekGuard = Runnable { seeking = false; flushSeek() }

    private var imageBaseMs = 0L
    private var imageStartedAt = 0L
    private var anchorCp = 0
    private var anchorUptime = 0L
    private var lastCp = -1

    private var nextPlayer: MediaPlayer? = null
    private var nextClipId: String? = null
    private var nextReady = false

    private class Aud(val itemId: String) {
        var mp: MediaPlayer? = null
        var prepared = false
        var started = false
    }

    private val auds = HashMap<String, Aud>()
    private var lastAudioTick = 0L

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            tick()
            if (playing) choreographer.postFrameCallback(this)
        }
    }

    init {
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                player?.setSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // Aplikasi ke latar / halaman ditutup: hentikan agar suara tidak jalan sendiri.
                pause()
                runCatching { player?.setSurface(null) }
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (texture.isAvailable) surface = texture.surfaceTexture?.let { Surface(it) }
    }

    // ------------------------------------------------------------------ queries

    fun currentClip(): VeClip? = loadedClipId?.let { id -> p.clips.firstOrNull { it.id == id } }

    /** Ukuran (belum diputar) video yang sedang dimuat, bila sudah diketahui. */
    fun videoSize(): Pair<Int, Int>? {
        val mp = player ?: return null
        if (!prepared) return null
        val w = runCatching { mp.videoWidth }.getOrDefault(0)
        val h = runCatching { mp.videoHeight }.getOrDefault(0)
        return if (w > 0 && h > 0) w to h else null
    }

    private fun clipStartOf(clip: VeClip): Long = p.clipStart(clip)

    private fun locate(ms: Long): Triple<VeClip, Long, Long>? {
        var t = 0L
        for (c in p.clips) {
            val d = c.timelineMs()
            if (ms < t + d) return Triple(c, (ms - t).coerceAtLeast(0L), t)
            t += d
        }
        val last = p.clips.lastOrNull() ?: return null
        return Triple(last, last.timelineMs(), t - last.timelineMs())
    }

    // ------------------------------------------------------------------ control

    /** Pindah ke [ms] (scrub / ketuk). Tetap memutar bila sedang memutar. */
    fun seek(ms: Long) {
        p.playheadMs = ms.coerceIn(0L, p.totalMs())
        if (released) return
        val hit = locate(p.playheadMs)
        if (hit == null) {
            clearMedia()
            syncAudio(true)
            return
        }
        val (clip, local, _) = hit
        showClip(clip, local.coerceAtMost(max(0L, clip.timelineMs() - 1)))
        syncAudio(true)
    }

    /** Muat ulang setelah data berubah (trim, kecepatan, undo…): posisi tetap, pemutar dipakai ulang bila bisa. */
    fun refresh() {
        applyVolume()
        // Pemutar "berikutnya" yang sudah disiapkan mungkin memakai trim lama (undo, ubah trim saat dijeda).
        discardNext()
        seek(p.playheadMs)
        if (playing) {
            // Kecepatan klip bisa berubah saat memutar: PlaybackParams hanya diterapkan oleh startPlayer.
            val clip = currentClip()
            val mp = player
            if (clip != null && !clip.isImage && mp != null && prepared) startPlayer(mp, clip)
        }
    }

    fun togglePlay() { if (playing) pause() else play() }

    fun play() {
        if (released) return
        if (p.clips.isEmpty()) { listener.onError("Impor media dulu"); return }
        if (p.playheadMs >= p.totalMs() - 50L) seek(0L)
        playing = true
        listener.onPlayState(true)
        val hit = locate(p.playheadMs)
        if (hit != null) {
            val (clip, local, _) = hit
            if (loadedClipId != clip.id) showClip(clip, local)
            if (clip.isImage) {
                imageBaseMs = local
                imageStartedAt = SystemClock.uptimeMillis()
            } else {
                val mp = player
                if (mp != null && prepared) startPlayer(mp, clip)
            }
        }
        syncAudio(true)
        choreographer.removeFrameCallback(frameCb)
        choreographer.postFrameCallback(frameCb)
    }

    fun pause() {
        val was = playing
        playing = false
        choreographer.removeFrameCallback(frameCb)
        runCatching { if (player?.isPlaying == true) player?.pause() }
        syncAudio(false)
        if (was) listener.onPlayState(false)
    }

    fun applyVolume() {
        val master = if (p.previewMuted) 0f else p.previewVolume
        val clip = currentClip()
        val v = if (p.includeAudio && clip != null) min(1f, clip.volume.coerceAtLeast(0f)) * master else 0f
        runCatching { player?.setVolume(v, v) }
        applyAudioVolumes(p.playheadMs)
    }

    fun release() {
        released = true
        playing = false
        choreographer.removeFrameCallback(frameCb)
        handler.removeCallbacks(seekGuard)
        releasePlayer()
        discardNext()
        for (ap in auds.values) releaseAud(ap)
        auds.clear()
        runCatching { surface?.release() }
        surface = null
        loadedClipId = null
        loadedUri = null
    }

    // ------------------------------------------------------------------ main clip

    private fun clearMedia() {
        releasePlayer()
        discardNext()
        loadedClipId = null
        loadedUri = null
        image.visibility = View.GONE
        texture.visibility = View.INVISIBLE
        listener.onMediaChanged()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(seekGuard)
        seeking = false
        pendingSeek = -1L
        pendingTargetMs = -1L
        runCatching { player?.setOnPreparedListener(null) }
        runCatching { player?.setOnSeekCompleteListener(null) }
        runCatching { player?.setOnCompletionListener(null) }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        prepared = false
        lastCp = -1
    }

    private fun showClip(clip: VeClip, local: Long) {
        if (loadedClipId == clip.id && loadedUri == clip.uri && (clip.isImage || player != null)) {
            if (clip.isImage) {
                imageBaseMs = local
                imageStartedAt = SystemClock.uptimeMillis()
            } else seekPlayer(clip, local)
            return
        }
        // Potongan berikutnya dari sumber yang sama dan berurutan: pemutar yang sama dipakai ulang.
        val cur = currentClip()
        if (cur != null && loadedUri == clip.uri && !clip.isImage && !cur.isImage && player != null && prepared && cur.effectiveSpeed() == clip.effectiveSpeed()) {
            loadedClipId = clip.id
            seekPlayer(clip, local)
            listener.onMediaChanged()
            return
        }
        loadClip(clip, local)
    }

    private fun loadClip(clip: VeClip, local: Long) {
        releasePlayer()
        discardNext()
        loadedClipId = clip.id
        loadedUri = clip.uri
        if (clip.isImage) {
            texture.visibility = View.INVISIBLE
            image.visibility = View.VISIBLE
            imageBaseMs = local
            imageStartedAt = SystemClock.uptimeMillis()
            image.setImageBitmap(p.thumbs["full:" + clip.uri] ?: p.thumbs[clip.uri.toString()])
            loadFullImage(clip)
            listener.onMediaChanged()
            return
        }
        image.visibility = View.GONE
        texture.visibility = View.VISIBLE
        val mp = MediaPlayer()
        player = mp
        prepared = false
        pendingTargetMs = clip.trimStartMs + (local * clip.effectiveSpeed()).toLong()
        try {
            mp.setDataSource(a, clip.uri)
            surface?.let { mp.setSurface(it) }
            attachMainListeners(mp, clip)
            mp.prepareAsync()
        } catch (e: Exception) {
            listener.onError("Pratinjau gagal: ${e.message}")
            releasePlayer()
        }
        listener.onMediaChanged()
    }

    private fun attachMainListeners(mp: MediaPlayer, clip: VeClip) {
        mp.setOnPreparedListener { pm ->
            if (player !== pm) return@setOnPreparedListener
            prepared = true
            val c = p.clips.firstOrNull { it.id == loadedClipId } ?: clip
            if (c.videoWidth <= 0) { c.videoWidth = pm.videoWidth; c.videoHeight = pm.videoHeight }
            applyVolume()
            val t = pendingTargetMs
            pendingTargetMs = -1L
            if (t >= 0L) issueSeek(pm, t)
            listener.onMediaChanged()
            if (playing) startPlayer(pm, c)
        }
        mp.setOnSeekCompleteListener { pm ->
            if (player !== pm) return@setOnSeekCompleteListener
            seeking = false
            handler.removeCallbacks(seekGuard)
            flushSeek()
        }
        mp.setOnCompletionListener { pm ->
            // Sumber habis sebelum tick mendeteksi akhir potongan (mis. durasi metadata sedikit lebih panjang).
            if (player === pm && playing) currentClip()?.let { advance(it) }
        }
        mp.setOnErrorListener { pm, _, _ ->
            if (player === pm) {
                listener.onError("Tidak bisa memutar ${clip.name}")
                pause()
            }
            true
        }
    }

    private fun loadFullImage(clip: VeClip) {
        val key = "full:" + clip.uri
        if (p.thumbs.containsKey(key)) {
            image.setImageBitmap(p.thumbs[key])
            return
        }
        Thread {
            val bmp = runCatching { VeAssets.decodeFit(a, clip.uri, 1600) }.getOrNull() ?: return@Thread
            handler.post {
                // Simpan hanya satu gambar penuh agar memori tetap ringan.
                p.thumbs.keys.filter { it.startsWith("full:") }.forEach { p.thumbs.remove(it) }
                p.thumbs[key] = bmp
                if (loadedClipId == clip.id) {
                    image.setImageBitmap(bmp)
                    listener.onMediaChanged()
                }
            }
        }.start()
    }

    private fun seekPlayer(clip: VeClip, local: Long) {
        val target = clip.trimStartMs + (local * clip.effectiveSpeed()).toLong()
        val pm = player ?: return
        if (!prepared) { pendingTargetMs = target; return }
        if (seeking) { pendingSeek = target; return }
        issueSeek(pm, target)
    }

    private fun issueSeek(pm: MediaPlayer, target: Long) {
        seeking = true
        pendingSeek = -1L
        handler.removeCallbacks(seekGuard)
        handler.postDelayed(seekGuard, 350L)
        lastCp = -1
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) pm.seekTo(target, MediaPlayer.SEEK_CLOSEST) else pm.seekTo(target.toInt())
        }
    }

    private fun flushSeek() {
        val pm = player ?: return
        val t = pendingSeek
        if (t >= 0L && prepared) issueSeek(pm, t)
    }

    /** Mulai pemutaran dengan kecepatan klip (PlaybackParams memulai playback, jadi hanya saat diputar). */
    private fun startPlayer(mp: MediaPlayer, clip: VeClip) {
        val speed = clip.effectiveSpeed()
        runCatching {
            if (speed != 1f || mp.playbackParams.speed != 1f) mp.playbackParams = mp.playbackParams.setSpeed(speed)
        }
        runCatching { mp.start() }
        lastCp = -1
        applyVolume()
    }

    // ------------------------------------------------------------------ tick

    private fun tick() {
        val clip = currentClip() ?: run { pause(); return }
        val start = clipStartOf(clip)
        val dur = clip.timelineMs()
        val now = SystemClock.uptimeMillis()
        val local: Long = if (clip.isImage) {
            imageBaseMs + (now - imageStartedAt)
        } else {
            val mp = player
            if (mp == null || !prepared) return
            val cp = runCatching { mp.currentPosition }.getOrDefault(0)
            val speed = clip.effectiveSpeed()
            if (cp != lastCp) { lastCp = cp; anchorCp = cp; anchorUptime = now }
            val extra = min(100L, ((now - anchorUptime) * speed).toLong())
            (((anchorCp + extra) - clip.trimStartMs) / speed).toLong().coerceAtLeast(0L)
        }
        maybePreloadNext(clip, local, dur)
        if (local >= dur - 25L) {
            advance(clip)
            return
        }
        p.playheadMs = start + local
        listener.onTime(p.playheadMs)
        if (now - lastAudioTick >= 90L) {
            lastAudioTick = now
            audioTick()
        }
    }

    private fun advance(clip: VeClip) {
        val idx = p.clips.indexOfFirst { it.id == clip.id }
        val next = p.clips.getOrNull(idx + 1)
        if (next == null) {
            p.playheadMs = p.totalMs()
            pause()
            listener.onTime(p.playheadMs)
            return
        }
        p.playheadMs = clipStartOf(next)
        val mp = player
        val contiguous = !clip.isImage && !next.isImage && next.uri == clip.uri && mp != null && prepared &&
            abs(next.trimStartMs - clip.effectiveEnd()) < 150L && next.effectiveSpeed() == clip.effectiveSpeed()
        when {
            contiguous -> {
                loadedClipId = next.id
                applyVolume()
                listener.onMediaChanged()
            }
            !next.isImage && nextPlayer != null && nextClipId == next.id && nextReady -> {
                swapToNext(next)
                listener.onMediaChanged()
            }
            else -> {
                loadClip(next, 0L)
                if (next.isImage) {
                    imageBaseMs = 0L
                    imageStartedAt = SystemClock.uptimeMillis()
                }
            }
        }
        listener.onTime(p.playheadMs)
        syncAudio(false)
    }

    // ------------------------------------------------------------------ preload

    private fun maybePreloadNext(clip: VeClip, local: Long, dur: Long) {
        if (nextPlayer != null || dur - local > 1800L) return
        val idx = p.clips.indexOfFirst { it.id == clip.id }
        val next = p.clips.getOrNull(idx + 1) ?: return
        if (next.isImage) return
        if (!clip.isImage && next.uri == clip.uri && abs(next.trimStartMs - clip.effectiveEnd()) < 150L) return
        val mp = MediaPlayer()
        nextPlayer = mp
        nextClipId = next.id
        nextReady = false
        try {
            mp.setDataSource(a, next.uri)
            mp.setOnPreparedListener { pm ->
                if (nextPlayer !== pm) return@setOnPreparedListener
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) pm.seekTo(next.trimStartMs, MediaPlayer.SEEK_CLOSEST) else pm.seekTo(next.trimStartMs.toInt())
                }
            }
            mp.setOnSeekCompleteListener { pm -> if (nextPlayer === pm) nextReady = true }
            mp.setOnErrorListener { pm, _, _ ->
                if (nextPlayer === pm) discardNext()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            discardNext()
        }
    }

    private fun discardNext() {
        val np = nextPlayer
        nextPlayer = null
        nextClipId = null
        nextReady = false
        if (np != null) {
            runCatching { np.setOnPreparedListener(null) }
            runCatching { np.setOnSeekCompleteListener(null) }
            runCatching { np.release() }
        }
    }

    private fun swapToNext(next: VeClip) {
        val np = nextPlayer ?: return
        val old = player
        nextPlayer = null
        nextClipId = null
        nextReady = false
        handler.removeCallbacks(seekGuard)
        seeking = false
        pendingSeek = -1L
        runCatching { old?.setOnPreparedListener(null) }
        runCatching { old?.setOnSeekCompleteListener(null) }
        runCatching { old?.setOnCompletionListener(null) }
        runCatching { old?.setSurface(null) }
        runCatching { old?.release() }
        player = np
        prepared = true
        loadedClipId = next.id
        loadedUri = next.uri
        // Klip sebelumnya bisa berupa foto: tampilkan kembali TextureView dan sembunyikan gambar.
        image.visibility = View.GONE
        texture.visibility = View.VISIBLE
        attachMainListeners(np, next)
        surface?.let { runCatching { np.setSurface(it) } }
        applyVolume()
        startPlayer(np, next)
    }

    // ------------------------------------------------------------------ audio items

    private fun newAud(item: VeAudioItem): Aud {
        val ap = Aud(item.id)
        val mp = MediaPlayer()
        ap.mp = mp
        try {
            mp.setDataSource(a, item.uri)
            mp.setOnPreparedListener {
                ap.prepared = true
                // force=false: hanya memulai item ini bila sudah di jendelanya; item lain tidak di-seek ulang.
                if (playing) syncAudio(false)
            }
            mp.setOnErrorListener { _, _, _ ->
                ap.prepared = false
                ap.mp = null
                runCatching { mp.release() }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            runCatching { mp.release() }
            ap.mp = null
        }
        return ap
    }

    private fun releaseAud(ap: Aud) {
        val mp = ap.mp
        ap.mp = null
        ap.prepared = false
        ap.started = false
        if (mp != null) {
            runCatching { mp.setOnPreparedListener(null) }
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
    }

    private fun audioGain(item: VeAudioItem, t: Long): Float {
        val master = if (p.previewMuted) 0f else p.previewVolume
        val rel = (t - item.startMs).coerceAtLeast(0L)
        val fade = VeMixer.fadeGain(rel.toInt(), item.durationMs().toInt(), item.fadeInMs.toInt(), item.fadeOutMs.toInt())
        return (min(1f, item.volume.coerceAtLeast(0f)) * fade * master).coerceIn(0f, 1f)
    }

    private fun applyAudioVolumes(t: Long) {
        for (item in p.audios) {
            val ap = auds[item.id] ?: continue
            val mp = ap.mp ?: continue
            val g = audioGain(item, t)
            runCatching { mp.setVolume(g, g) }
        }
    }

    private fun seekAud(mp: MediaPlayer, target: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) mp.seekTo(target, MediaPlayer.SEEK_CLOSEST) else mp.seekTo(target.toInt())
        }
    }

    /**
     * Samakan pemutar audio dengan playhead: buat bagi item yang aktif/segera mulai, mulai/seek bila [force],
     * jeda saat tidak memutar, lepas yang sudah lewat.
     */
    private fun syncAudio(force: Boolean) {
        if (released) return
        val t = p.playheadMs
        val live = HashSet<String>()
        for (item in p.audios) {
            val inWindow = t >= item.startMs && t < item.endMs()
            val soon = playing && !inWindow && item.startMs > t && item.startMs - t < 1500L
            if (!inWindow && !soon) continue
            live.add(item.id)
            var ap = auds[item.id]
            if (ap == null) {
                if (!playing) continue
                ap = newAud(item)
                auds[item.id] = ap
            }
            val mp = ap.mp ?: continue
            val g = audioGain(item, t)
            runCatching { mp.setVolume(g, g) }
            if (!playing) {
                if (ap.started) { runCatching { if (mp.isPlaying) mp.pause() }; ap.started = false }
                continue
            }
            if (!ap.prepared || !inWindow) continue
            val target = item.srcStartMs + (t - item.startMs)
            if (!ap.started || force) {
                seekAud(mp, target)
                runCatching { mp.start() }
                ap.started = true
            }
        }
        val it = auds.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in live) {
                releaseAud(e.value)
                it.remove()
            }
        }
    }

    /** Koreksi drift & perbarui volume (fade) ~11×/detik saat memutar. */
    private fun audioTick() {
        val t = p.playheadMs
        var needSync = false
        for (item in p.audios) {
            val inWindow = t >= item.startMs && t < item.endMs()
            val ap = auds[item.id]
            if (inWindow && ap == null) { needSync = true; continue }
            if (!inWindow && ap != null) { needSync = true; continue }
            if (ap == null) {
                if (item.startMs > t && item.startMs - t < 1500L) needSync = true
                continue
            }
            val mp = ap.mp ?: continue
            val g = audioGain(item, t)
            runCatching { mp.setVolume(g, g) }
            // Dibuat lebih awal (jendela "segera mulai") dan baru sekarang masuk jendelanya: mulai.
            if (inWindow && ap.prepared && !ap.started) { needSync = true; continue }
            if (ap.started && ap.prepared && inWindow) {
                val want = item.srcStartMs + (t - item.startMs)
                val have = runCatching { mp.currentPosition }.getOrDefault(0).toLong()
                if (abs(have - want) > 320L) seekAud(mp, want)
            }
        }
        if (needSync) syncAudio(false)
    }
}
