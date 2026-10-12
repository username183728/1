package com.example.aidetest

import android.animation.Animator
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable

/**
 * Bit animation — idle + interaktif drag.
 *
 * Idle: loop kedip + lihat kiri/kanan/atas/bawah (frame 0–108).
 * Interaktif (tekan & geser): mata mengikuti jari secara halus (Choreographer),
 * badan ikut digeser + miring 3D; lepas jari → spring kembali ke posisi awal.
 * Reaksi live lama (LOVE/BUMP/DIZZY/…) tetap dinonaktifkan.
 */
internal class BitAnimationController(private val activity: MainActivity) : SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    // Weak keys prevent page-owned Lottie views from being retained by the controller
    // after a navigation rebuild. Active views remain alive through their parent.
    private val faces = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<LottieAnimationView, Boolean>()
    )
    private var token = 0
    private var lastLookAtAt = 0L
    private var lifecycleActive = true

    // Early idle frames: body neutral, eyes only.
    // 11 = blink, 28 = look left, 58 = look right.
    // Up/down approximated with blink / slight vertical seek in the same range.
    private companion object {
        const val IDLE_END = 108
        const val GAZE_TAU_MS = 48f      // penghalusan gaze (lebih responsif, tetap mulus)
        const val TILT_DEG = 18f         // kemiringan 3D maksimum badan
        const val BODY_SHIFT = 0.04f     // pergeseran badan (fraksi lebar) ke arah jari saat gaze
        const val DRAG_MAX_FRAC = 0.55f  // batas geser badan (fraksi lebar wajah)
        const val PRESS_SCALE = 0.96f    // scale saat ditekan
        const val SPRING_MS = 320L       // durasi spring-back ke posisi awal
        // Balik tanda ini bila arah miringnya terasa terbalik di perangkat.
        const val TILT_SIGN_X = 1f
        const val TILT_SIGN_Y = -1f
    }

    /** State drag badan per wajah (translation dikendalikan finger). */
    private class DragState {
        var active = false
        var baseTx = 0f
        var baseTy = 0f
    }
    private val dragStates = java.util.WeakHashMap<LottieAnimationView, DragState>()
    /** Hanya satu wajah yang digeser — cegah update ganda / "bot dobel". */
    private var exclusiveDragFace: LottieAnimationView? = null
    val isExclusiveDragging: Boolean get() = exclusiveDragFace != null

    private class GazeState {
        var curX = 0f; var curY = 0f
        var tgtX = 0f; var tgtY = 0f
        var releasing = false
        var lastFrame = -1
        var entered = false
    }
    private val gazeStates = java.util.WeakHashMap<LottieAnimationView, GazeState>()
    private var gazeTicking = false
    private var gazeLastNs = 0L
    private var gazeReleaseRunnable: Runnable? = null
    private val gazeFrameCb = android.view.Choreographer.FrameCallback { stepGaze(it) }

    fun attach(face: LottieAnimationView) {
        faces.add(face)
        configure(face)
        loadIdle(face)
    }

    fun detach(face: LottieAnimationView?) {
        if (face != null) {
            faces.remove(face)
            face.removeAllAnimatorListeners()
            face.cancelAnimation()
        }
    }

    /** Tap singkat → animasi tap Lottie, lalu idle. */
    fun onTap(face: LottieAnimationView? = null) {
        playReaction(face ?: activity.bitFace, "bit_tap.json")
    }

    fun playLove(face: LottieAnimationView? = null) {
        playReaction(face ?: activity.bitFace, "bit_love.json")
    }

    fun playSleep() {
        // Sleep: soft pose + slow idle loop (asset khusus belum ada)
        faces.forEach { f ->
            if (f === exclusiveDragFace) return@forEach
            playIdle(f)
            if (Motion.enabled) {
                f.animate().cancel()
                f.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.88f).setDuration(400L).start()
            }
        }
    }

    fun playThinking(face: LottieAnimationView? = null) {
        playReaction(face ?: activity.bitFace, "bit_wink.json")
    }

    fun playTap(face: LottieAnimationView? = null) {
        playReaction(face ?: activity.bitFace, "bit_tap.json")
    }

    fun playBump(face: LottieAnimationView? = null) {
        playReaction(face ?: activity.bitFace, "bit_bump.json")
    }

    fun playBump() {
        ++token
        faces.filter { it !== exclusiveDragFace }.forEach { playReaction(it, "bit_bump.json") }
    }

    fun playDizzy() {
        ++token
        faces.filter { it !== exclusiveDragFace }.forEach { playReaction(it, "bit_dizzy.json") }
    }

    fun playError() {
        ++token
        faces.filter { it !== exclusiveDragFace }.forEach { playReaction(it, "bit_error.json") }
    }

    fun playSad(face: LottieAnimationView? = null) {
        playReaction(face ?: activity.bitFace, "bit_sad.json")
    }

    fun playWink(face: LottieAnimationView? = null) {
        playReaction(face ?: activity.bitFace, "bit_wink.json")
    }

    fun playRecovered() {
        playIdleAll()
    }

    /**
     * Putar clip Lottie reaction sekali, lalu kembali idle.
     * Asset: bit_love / bit_tap / bit_bump / bit_dizzy / bit_error / bit_sad / bit_wink.
     */
    fun playReaction(face: LottieAnimationView?, assetFile: String) {
        val target = face ?: activity.bitFace ?: return
        if (!target.isAttachedToWindow) return
        if (exclusiveDragFace === target) return
        ++token
        val myToken = token
        // Soft 3D punch on reaction start
        BitSoftMotion.applySoft3DPulse(target)
        playAssetFull(target, assetFile, myToken) {
            if (myToken == token) playIdle(target)
        }
    }

    fun playIdleAll() {
        ++token
        faces.filter { it !== exclusiveDragFace }.forEach(::playIdle)
    }

    /** Muat ulang semua wajah Bit setelah bentuk badan diganti. */
    fun reloadShape() {
        faces.forEach { loadIdle(it) }
        playIdleAll()
    }

    fun refreshTheme() {
        faces.forEach { activity.applyBitFaceTheme(it) }
    }

    /** Tekan singkat: scale down + masuk mode gaze (mata siap bergerak). */
    fun onPressStart(face: LottieAnimationView? = null) {
        val target = face ?: activity.bitFace ?: return
        if (!target.isAttachedToWindow) return
        // Hentikan idle agar frame gaze bisa di-seek tanpa bentrok layer.
        ++token
        target.animate().cancel()
        ensureGazeComposition(target)
        enterGazeImmediate(target)
        // Mata sedikit ke bawah saat ditekan (kesan "fokus ke jari")
        setGazeTarget(target, 0f, 0.35f)
        startGazeTicker()
        if (Motion.enabled) {
            target.animate()
                .scaleX(PRESS_SCALE)
                .scaleY(PRESS_SCALE - 0.015f)
                .rotation(-1.4f)
                .setDuration(100L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
        }
    }

    /** Lepas tekan tanpa drag: scale kembali + idle. */
    fun onPressEnd(face: LottieAnimationView? = null) {
        val target = face ?: activity.bitFace ?: return
        if (exclusiveDragFace === target) return
        if (Motion.enabled) {
            target.animate().cancel()
            target.animate()
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setDuration(160L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                .start()
        } else {
            target.scaleX = 1f
            target.scaleY = 1f
        }
        releaseGazeFor(target)
    }

    /**
     * Drag start: kunci wajah ini saja (exclusive), hentikan idle, siapkan gaze.
     * Tidak menyentuh wajah lain → mencegah "muncul 2 bot".
     */
    fun onDragStart(face: LottieAnimationView? = null) {
        val target = face ?: activity.bitFace ?: return
        ++token
        exclusiveDragFace = target
        target.animate().cancel()
        // Reset transform sisa animasi spring sebelumnya
        target.translationX = 0f
        target.translationY = 0f
        target.rotationX = 0f
        target.rotationY = 0f
        target.rotation = 0f
        target.cameraDistance = target.resources.displayMetrics.density * 8000f
        // Kunci wajah lain agar tidak ikut bergeser (mencegah "bot dobel")
        faces.forEach { other ->
            if (other !== target) {
                other.animate().cancel()
                other.translationX = 0f
                other.translationY = 0f
                other.rotationX = 0f
                other.rotationY = 0f
            }
        }
        val st = dragStates.getOrPut(target) { DragState() }
        st.active = true
        st.baseTx = 0f
        st.baseTy = 0f
        ensureGazeComposition(target)
        enterGazeImmediate(target)
        if (Motion.enabled && target.scaleX > PRESS_SCALE + 0.01f) {
            target.scaleX = PRESS_SCALE
            target.scaleY = PRESS_SCALE
        }
    }

    private fun nxFromDrag(tx: Float, reach: Float): Float =
        (tx / reach.coerceAtLeast(1f)).coerceIn(-1f, 1f)

    /**
     * Drag move: geser HANYA translation (tanpa rotationX/Y yang sering bikin ghost/dobel di GPU).
     * Mata diarahkan ke vektor geser lewat seek frame gaze.
     */
    fun onDragMove(face: LottieAnimationView? = null, dx: Float, dy: Float) {
        val target = face ?: activity.bitFace ?: return
        if (!target.isAttachedToWindow) return
        if (exclusiveDragFace !== target) {
            // Amankan exclusive bila onDragStart terlewat
            onDragStart(target)
        }
        val st = dragStates[target] ?: return
        if (!st.active) return

        val max = (target.width.coerceAtLeast(1) * DRAG_MAX_FRAC)
        val tx = (st.baseTx + dx).coerceIn(-max, max)
        val ty = (st.baseTy + dy).coerceIn(-max, max)
        target.translationX = tx
        target.translationY = ty
        // Mochi tilt kecil mengikuti arah drag tanpa 3D rotationX/Y.
        target.rotation = nxFromDrag(tx, reach = max.coerceAtLeast(1f)) * 4.0f

        // Arah mata dari vektor geser (bukan raw screen + dx dobel)
        val reach = max.coerceAtLeast(1f)
        val nx = (tx / reach).coerceIn(-1f, 1f)
        val ny = (ty / reach).coerceIn(-1f, 1f)
        setGazeTarget(target, nx, ny)
        startGazeTicker()
    }

    /**
     * Drag end: spring kembali, lepas exclusive, idle.
     */
    fun onDragEnd(face: LottieAnimationView? = null) {
        val target = face ?: activity.bitFace ?: return
        val st = dragStates[target]
        if (st != null) st.active = false
        dragStates.remove(target)
        if (exclusiveDragFace === target) exclusiveDragFace = null

        releaseGazeFor(target)

        if (!Motion.enabled) {
            target.translationX = 0f
            target.translationY = 0f
            target.rotationX = 0f
            target.rotationY = 0f
            target.scaleX = 1f
            target.scaleY = 1f
            playIdle(target)
            return
        }

        target.animate().cancel()
        target.animate()
            .translationX(0f)
            .translationY(0f)
            .rotationX(0f)
            .rotationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SPRING_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.35f))
            .withEndAction {
                if (!activity.isFinishing && target.isAttachedToWindow) {
                    target.translationX = 0f
                    target.translationY = 0f
                    target.rotationX = 0f
                    target.rotationY = 0f
                    playIdle(target)
                }
            }
            .start()
    }

    /**
     * Mata mengikuti posisi jari (global touch). Lewati wajah yang sedang di-drag exclusive
     * agar tidak dobel-update translation/tilt.
     */
    fun lookAt(rawX: Float, rawY: Float, holdMs: Long = 200L) {
        if (!Motion.enabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLookAtAt < 8L) return
        lastLookAtAt = now

        var any = false
        for (face in faces.toList()) {
            if (face === exclusiveDragFace) continue
            if (dragStates[face]?.active == true) continue
            if (!face.isShown || face.width == 0 || !face.isAttachedToWindow) continue
            if (!hasGazeFrames(face)) {
                ensureGazeComposition(face)
                if (!hasGazeFrames(face)) continue
            }
            val loc = IntArray(2)
            face.getLocationOnScreen(loc)
            val dx = rawX - (loc[0] + face.width * 0.5f)
            val dy = rawY - (loc[1] + face.height * 0.5f)
            val reach = maxOf(face.width * 0.8f, activity.dp(110).toFloat())
            var nx = dx / reach
            var ny = dy / reach
            val len = kotlin.math.hypot(nx.toDouble(), ny.toDouble()).toFloat()
            if (len > 1f) { nx /= len; ny /= len }
            setGazeTarget(face, nx, ny)
            any = true
        }
        if (!any) return
        startGazeTicker()

        gazeReleaseRunnable?.let(handler::removeCallbacks)
        val r = Runnable { releaseGaze() }
        gazeReleaseRunnable = r
        handler.postDelayed(r, holdMs)
    }

    /** Pastikan komposisi idle+gaze termuat (frame 1000+ tersedia). */
    private fun ensureGazeComposition(face: LottieAnimationView) {
        if (hasGazeFrames(face)) return
        face.setBitAsset("bit/bit_idle.json", withGaze = true)
        activity.applyBitFaceTheme(face)
        face.setMinAndMaxFrame(0, BitShape.gazeEndFrame)
    }

    private fun enterGazeImmediate(face: LottieAnimationView) {
        var st = gazeStates[face]
        if (st == null) {
            st = GazeState()
            gazeStates[face] = st
        }
        face.removeAllAnimatorListeners()
        if (face.isAnimating) face.cancelAnimation()
        face.setMinAndMaxFrame(0, BitShape.gazeEndFrame)
        st.entered = true
        st.releasing = false
        st.lastFrame = -1
    }

    /** nx, ny dalam -1..1 */
    private fun setGazeTarget(face: LottieAnimationView, nx: Float, ny: Float) {
        var st = gazeStates[face]
        if (st == null) {
            st = GazeState()
            gazeStates[face] = st
            ++token
        }
        st.tgtX = nx.coerceIn(-1f, 1f) * BitShape.GAZE_MAX_X.toFloat()
        st.tgtY = ny.coerceIn(-1f, 1f) * BitShape.GAZE_MAX_Y.toFloat()
        st.releasing = false
        if (!st.entered) enterGazeImmediate(face)
    }

    private fun releaseGazeFor(face: LottieAnimationView) {
        val st = gazeStates[face] ?: return
        st.tgtX = 0f
        st.tgtY = 0f
        st.releasing = true
        startGazeTicker()
    }

    private fun hasGazeFrames(face: LottieAnimationView): Boolean =
        (face.composition?.endFrame ?: 0f) >= BitShape.gazeEndFrame - 1

    /** Mata kembali ke tengah lalu animasi idle dilanjutkan. */
    fun releaseGaze() {
        gazeReleaseRunnable?.let(handler::removeCallbacks)
        gazeReleaseRunnable = null
        if (gazeStates.isEmpty()) return
        for (st in gazeStates.values) { st.tgtX = 0f; st.tgtY = 0f; st.releasing = true }
        startGazeTicker()
    }

    private fun startGazeTicker() {
        if (!lifecycleActive || activity.isFinishing) return
        if (gazeTicking) return
        gazeTicking = true
        gazeLastNs = 0L
        android.view.Choreographer.getInstance().postFrameCallback(gazeFrameCb)
    }

    /** Masuk mode gaze sekali saja: hentikan loop idle dan kunci rentang frame. */
    private fun enterGaze(face: LottieAnimationView, st: GazeState) {
        face.removeAllAnimatorListeners()
        if (face.isAnimating) face.cancelAnimation()
        face.setMinAndMaxFrame(0, BitShape.gazeEndFrame)
        face.cameraDistance = face.resources.displayMetrics.density * 9000f
        st.entered = true
        st.lastFrame = -1
    }

    private fun resetBodyTilt(face: LottieAnimationView) {
        face.rotationX = 0f
        face.rotationY = 0f
        face.translationX = 0f
        face.translationY = 0f
    }

    private fun stepGaze(frameTimeNs: Long) {
        if (!lifecycleActive || activity.isFinishing) {
            gazeTicking = false
            return
        }
        val dtMs = if (gazeLastNs == 0L) 16f else
            ((frameTimeNs - gazeLastNs) / 1_000_000f).coerceIn(1f, 50f)
        gazeLastNs = frameTimeNs
        // Penghalusan eksponensial berbasis waktu -> sama halusnya di 60/90/120 Hz.
        val k = 1f - kotlin.math.exp(-dtMs / GAZE_TAU_MS)

        var active = false
        var finished = false
        for ((face, st) in gazeStates.entries.toList()) {
            if (activity.isFinishing || !face.isAttachedToWindow || !hasGazeFrames(face)) {
                gazeStates.remove(face); continue
            }
            st.curX += (st.tgtX - st.curX) * k
            st.curY += (st.tgtY - st.curY) * k
            if (!st.entered || face.isAnimating) enterGaze(face, st)

            // 1) Mata: pilih frame grid terdekat (langkah ~0,6dp).
            val nxv = (st.curX / BitShape.GAZE_MAX_X.toFloat()).coerceIn(-1f, 1f)
            val nyv = (st.curY / BitShape.GAZE_MAX_Y.toFloat()).coerceIn(-1f, 1f)
            val col = Math.round((nxv + 1f) / 2f * (BitShape.GAZE_COLS - 1)).coerceIn(0, BitShape.GAZE_COLS - 1)
            val row = Math.round((nyv + 1f) / 2f * (BitShape.GAZE_ROWS - 1)).coerceIn(0, BitShape.GAZE_ROWS - 1)
            val frame = BitShape.gazeFrame(col, row)
            if (frame != st.lastFrame) {
                st.lastFrame = frame
                face.frame = frame
            }

            // 2) Badan: hanya miring sangat ringan saat gaze (bukan drag).
            //    JANGAN translation di sini — translation hanya dari onDragMove,
            //    supaya tidak muncul "bot dobel" (ghost di posisi lama + posisi baru).
            val dragging = dragStates[face]?.active == true || face === exclusiveDragFace
            if (!dragging && !st.releasing) {
                face.rotationY = nxv * (TILT_DEG * 0.45f) * TILT_SIGN_X
                face.rotationX = nyv * (TILT_DEG * 0.45f) * TILT_SIGN_Y
            }

            val settled = kotlin.math.abs(st.tgtX - st.curX) < 0.05f && kotlin.math.abs(st.tgtY - st.curY) < 0.05f
            if (st.releasing && (settled || (kotlin.math.abs(st.curX) < 0.25f && kotlin.math.abs(st.curY) < 0.25f))) {
                if (face !== exclusiveDragFace) {
                    resetBodyTilt(face)
                }
                gazeStates.remove(face)
                finished = true
                continue
            }
            if (!settled || st.releasing) active = true
        }
        // Jangan playIdleAll saat masih drag/spring — onDragEnd yang mengurus idle.
        if (finished && gazeStates.isEmpty() && exclusiveDragFace == null) {
            faces.filter { dragStates[it]?.active != true }.forEach { playIdle(it) }
        }
        if (active) {
            android.view.Choreographer.getInstance().postFrameCallback(gazeFrameCb)
        } else {
            gazeTicking = false
        }
    }

    fun startShakeDetection() {
        // Shake tidak memicu animasi apa pun.
        stopShakeDetection()
    }

    fun setLifecycleActive(active: Boolean) {
        lifecycleActive = active
        if (!active) {
            stopShakeDetection()
            android.view.Choreographer.getInstance().removeFrameCallback(gazeFrameCb)
            gazeTicking = false
        }
    }

    fun dispose() {
        setLifecycleActive(false)
        faces.toList().forEach(::detach)
        gazeStates.clear()
        dragStates.clear()
        exclusiveDragFace = null
    }

    fun stopShakeDetection() {
        // no-op: sensor tidak dipakai
    }

    override fun onSensorChanged(event: SensorEvent) = Unit
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun configure(face: LottieAnimationView) {
        face.repeatMode = LottieDrawable.RESTART
        face.repeatCount = 0
        face.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        face.isClickable = false
        face.isFocusable = false
    }

    fun playIdle(face: LottieAnimationView?) {
        if (face == null || !faces.contains(face)) return
        val myToken = token
        // Restore pose from sleep/reaction
        if (face.alpha < 0.99f || face.scaleX < 0.98f) {
            face.animate().cancel()
            face.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start()
        }
        // Idle: full blink/gaze range in bit_idle (0–108 used for responsive loop)
        playAsset(face, "bit_idle.json", 0, IDLE_END, myToken) {
            if (myToken == token) playIdle(face)
        }
    }

    /** Play entire composition of a reaction asset (uses file ip..op). */
    private fun playAssetFull(face: LottieAnimationView, assetFile: String, myToken: Int, onEnd: (() -> Unit)? = null) {
        if (!faces.contains(face) || myToken != token) return
        face.removeAllAnimatorListeners()
        face.cancelAnimation()
        face.setBitAsset("bit/$assetFile", withGaze = false)
        activity.applyBitFaceTheme(face)
        face.repeatCount = 0
        // Defer to composition bounds after load
        face.post {
            if (myToken != token || !faces.contains(face)) return@post
            val comp = face.composition
            if (comp != null) {
                val start = comp.startFrame.toInt()
                val end = comp.endFrame.toInt().coerceAtLeast(start + 1)
                face.setMinAndMaxFrame(start, end)
            }
            face.playAnimation()
        }
        face.addAnimatorListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) = Unit
            override fun onAnimationCancel(animation: Animator) = Unit
            override fun onAnimationRepeat(animation: Animator) = Unit
            override fun onAnimationEnd(animation: Animator) {
                if (myToken != token || !faces.contains(face)) return
                onEnd?.invoke()
            }
        })
    }

    private fun loadIdle(face: LottieAnimationView) {
        face.setBitAsset("bit/bit_idle.json", withGaze = true)
        activity.applyBitFaceTheme(face)
        face.setMinAndMaxFrame(0, IDLE_END)
    }

    private fun playAsset(
        face: LottieAnimationView?,
        asset: String,
        start: Int,
        end: Int,
        myToken: Int,
        onEnd: (() -> Unit)? = null
    ) {
        if (face == null || !faces.contains(face) || myToken != token) return
        face.removeAllAnimatorListeners()
        face.cancelAnimation()
        face.setBitAsset("bit/$asset", withGaze = asset == "bit_idle.json")
        activity.applyBitFaceTheme(face)
        face.repeatCount = 0
        face.setMinAndMaxFrame(start, end)
        face.scaleX = 1f
        face.scaleY = 1f
        face.rotation = 0f
        if (!gazeStates.containsKey(face)) {
            face.translationX = 0f
            face.translationY = 0f
            face.rotationX = 0f
            face.rotationY = 0f
        }
        face.addAnimatorListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) = Unit
            override fun onAnimationCancel(animation: Animator) = Unit
            override fun onAnimationRepeat(animation: Animator) = Unit
            override fun onAnimationEnd(animation: Animator) {
                if (myToken != token || !faces.contains(face)) return
                onEnd?.invoke()
            }
        })
        face.playAnimation()
    }
}
