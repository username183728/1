package com.example.aidetest

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Motion Design System MyTools — satu-satunya sumber animasi UI.
 *
 * Prinsip: "Motion should communicate, not decorate." / "Consistent system, individual character."
 * - Semua animasi berbasis ViewPropertyAnimator (GPU-friendly: alpha/scale/translation/rotation).
 * - Tidak ada infinite animation kecuali loading, dan itu otomatis berhenti saat view lepas/tersembunyi.
 * - Mode High / Mid / Low / Off dibaca dari SharedPreferences ("motion_mode").
 *   Pref lama "ui_animations"=false otomatis dianggap Off (kompatibel dengan versi sebelumnya).
 * - Durasi dasar memakai Ds.ANIM_FAST / Ds.ANIM_NORMAL agar tidak ada sistem kedua.
 */
object Motion {

    // ---------- Token durasi (ms) ----------
    const val MOTION_FAST = Ds.ANIM_FAST          // tap/press
    const val MOTION_NORMAL = Ds.ANIM_NORMAL      // icon, card
    const val MOTION_SLOW = 320L                  // page/workspace
    const val MOTION_LOADING = 900L               // satu putaran loading

    // ---------- Token easing ----------
    val EASE_OUT: Interpolator = DecelerateInterpolator(1.6f)
    val EASE_IN_OUT: Interpolator = AccelerateDecelerateInterpolator()
    val SPRING_SOFT: Interpolator = OvershootInterpolator(1.8f)

    enum class Mode { HIGH, MID, LOW, OFF }

    /** Karakter visual per Tool. */
    enum class Character { DIGITAL, STRUCTURED, TECHNICAL, PROCESS, CALM, VISUAL, DEFAULT }

    /** Jenis reaksi ikon yang bisa dipakai ulang. */
    enum class Icon { TAP, SUCCESS, REFRESH, FAVORITE, DELETE, DOWNLOAD, UPLOAD, SEARCH, SETTINGS }

    private const val PREF_MODE = "motion_mode"
    private const val PREF_LEGACY = "ui_animations"

    @Volatile private var cachedMode: Mode = Mode.HIGH
    @Volatile private var systemOff: Boolean = false

    /** Panggil sekali di onCreate dan setiap pengaturan berubah. */
    fun refresh(prefs: SharedPreferences, resolver: android.content.ContentResolver) {
        systemOff = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        cachedMode = readMode(prefs)
    }

    fun readMode(prefs: SharedPreferences): Mode {
        val raw = prefs.getString(PREF_MODE, null)
        if (raw != null) return when (raw) {
            "FULL" -> Mode.HIGH       // compatibility with <= 2.43.5
            "REDUCED" -> Mode.MID     // compatibility with <= 2.43.5
            "HIGH", "MID", "LOW", "OFF" -> Mode.valueOf(raw)
            else -> Mode.HIGH
        }
        return if (prefs.getBoolean(PREF_LEGACY, true)) Mode.HIGH else Mode.OFF
    }

    fun writeMode(prefs: SharedPreferences, mode: Mode) {
        prefs.edit()
            .putString(PREF_MODE, mode.name)
            .putBoolean(PREF_LEGACY, mode != Mode.OFF)
            .apply()
    }

    fun nextMode(mode: Mode): Mode = when (mode) {
        Mode.HIGH -> Mode.MID
        Mode.MID -> Mode.LOW
        Mode.LOW -> Mode.OFF
        Mode.OFF -> Mode.HIGH
    }

    fun label(mode: Mode): String = when (mode) {
        Mode.HIGH -> "High"
        Mode.MID -> "Mid"
        Mode.LOW -> "Low"
        Mode.OFF -> "Off"
    }

    fun description(mode: Mode): String = when (mode) {
        Mode.HIGH -> "Efek paling lengkap: transisi bertingkat, spring, stagger, reaksi ikon, dan loading yang lebih hidup."
        Mode.MID -> "Efek standar: transisi halus dan reaksi tombol tanpa efek kompleks."
        Mode.LOW -> "Efek ringan: fade/feedback singkat, minim gerakan dan beban GPU."
        Mode.OFF -> "Tanpa animasi; perubahan UI langsung."
    }

    val mode: Mode get() = if (systemOff) Mode.OFF else cachedMode
    val enabled: Boolean get() = mode != Mode.OFF
    private val full: Boolean get() = mode == Mode.HIGH

    /** Skala durasi per level. High = paling kaya, Mid = standar, Low = sangat ringan. */
    private fun d(ms: Long): Long = when (mode) {
        Mode.HIGH -> ms
        Mode.MID -> (ms * 0.68f).toLong().coerceAtLeast(55L)
        Mode.LOW -> (ms * 0.42f).toLong().coerceAtLeast(45L)
        Mode.OFF -> 0L
    }

    private fun dp(v: View, n: Float) = n * v.resources.displayMetrics.density

    private fun settle(v: View) {
        v.animate().cancel()
        v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
        v.translationX = 0f; v.translationY = 0f; v.rotation = 0f
    }

    // ---------- Press feedback (tombol, card, ghost button) ----------
    private val pressBound = WeakHashMap<View, Boolean>()

    /**
     * Pasang feedback sentuh. Idempotent: pemanggilan kedua pada view yang sama diabaikan,
     * sehingga reaksi ikon khusus Tool Card tidak tertimpa helper generik.
     * Listener mengembalikan false → onClick tetap berjalan normal.
     */
    fun press(
        view: View,
        scale: Float = 0.975f,
        shadowDp: Float = 0f,
        onDown: (() -> Unit)? = null,
        onRelease: (() -> Unit)? = null
    ) {
        if (pressBound.containsKey(view)) return
        pressBound[view] = true
        view.isClickable = true
        var baseAlpha = 1f   // alpha asli view (mis. tombol disabled 0.5) dipulihkan saat dilepas
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    baseAlpha = if (v.alpha in 0.93f..0.995f) 1f else v.alpha
                    // shadow tidak boleh menjadi negatif total (mencegah view tertutup sibling)
                    val z = maxOf(dp(v, shadowDp), -v.elevation)
                    when (mode) {
                        Mode.OFF -> v.alpha = baseAlpha * 0.85f       // feedback minimal tetap ada
                        Mode.LOW -> v.animate().alpha(baseAlpha * 0.94f).setDuration(d(70L))
                            .setInterpolator(EASE_OUT).start()
                        else -> v.animate().scaleX(scale).scaleY(scale).alpha(baseAlpha * 0.94f)
                            .translationZ(z)
                            .setDuration(d(90L)).setInterpolator(EASE_OUT).start()
                    }
                    onDown?.invoke()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    when (mode) {
                        Mode.OFF -> { v.alpha = baseAlpha; v.scaleX = 1f; v.scaleY = 1f; v.translationZ = 0f }
                        Mode.LOW -> v.animate().scaleX(1f).scaleY(1f).alpha(baseAlpha).translationZ(0f)
                            .setDuration(d(MOTION_FAST)).setInterpolator(EASE_OUT).start()
                        Mode.MID -> v.animate().scaleX(1f).scaleY(1f).alpha(baseAlpha).translationZ(0f)
                            .setDuration(d(MOTION_FAST)).setInterpolator(EASE_OUT).start()
                        Mode.HIGH -> v.animate().scaleX(1f).scaleY(1f).alpha(baseAlpha).translationZ(0f)
                            .setDuration(d(MOTION_NORMAL)).setInterpolator(SPRING_SOFT).start()
                    }
                    if (e.actionMasked == MotionEvent.ACTION_UP) onRelease?.invoke()
                }
            }
            false
        }
    }

    /** Tap sekali dari kode (mis. ghPressable): scale turun lalu kembali. */
    fun tap(view: View, scale: Float = 0.97f) {
        settle(view)
        if (!enabled) return
        view.animate().scaleX(scale).scaleY(scale).setDuration(d(70L)).setInterpolator(EASE_OUT)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(d(MOTION_FAST))
                    .setInterpolator(if (full) SPRING_SOFT else EASE_OUT).start()
            }.start()
    }

    // ---------- Masuk / keluar ----------
    /** Elemen masuk: fast-in, smooth-out. */
    fun enter(view: View, delay: Long = 0L, dy: Float = 10f, duration: Long = MOTION_NORMAL) {
        if (!enabled) { settle(view); return }
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = if (full) dp(view, dy) else 0f
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(if (full) delay else 0L)
            .setDuration(d(duration)).setInterpolator(EASE_OUT).start()
    }

    /** List muncul bertahap sangat cepat; dibatasi agar tidak berat (maks 8 anak, 24 ms/langkah). */
    fun stagger(parent: android.view.ViewGroup, maxItems: Int = 8, stepMs: Long = 24L) {
        val n = minOf(parent.childCount, maxItems)
        for (i in 0 until n) enter(parent.getChildAt(i), delay = i * stepMs, dy = 8f, duration = 180L)
    }

    /**
     * Transisi "membuka workspace" untuk konten Tool. Karakter menentukan gaya masuk.
     * Dipanggil sekali per openTool, menggantikan blok animasi lama.
     */
    fun openWorkspace(content: View, character: Character) {
        if (!enabled) { settle(content); return }
        content.animate().cancel()
        content.alpha = 0f
        content.translationX = 0f
        content.translationY = 0f
        content.scaleX = 1f; content.scaleY = 1f
        if (full) when (character) {
            Character.DIGITAL, Character.VISUAL -> { content.scaleX = 0.97f; content.scaleY = 0.97f }
            Character.STRUCTURED -> content.translationX = dp(content, 12f)
            Character.TECHNICAL -> content.translationY = dp(content, 6f)
            Character.PROCESS -> content.translationY = dp(content, 14f)
            Character.CALM, Character.DEFAULT -> content.translationY = dp(content, 4f)
        }
        content.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(d(if (character == Character.PROCESS) MOTION_SLOW else 240L))
            .setInterpolator(if (character == Character.CALM) EASE_IN_OUT else EASE_OUT)
            .start()
    }

    /** Geser halaman root (swipe/nav): fade + slide ringan. */
    fun pageSlide(content: View, fromStartDp: Float) {
        if (!enabled) { settle(content); return }
        content.animate().cancel()
        content.translationX = if (full) dp(content, fromStartDp) else 0f
        content.alpha = 0f
        content.animate().translationX(0f).alpha(1f).setDuration(d(200L))
            .setInterpolator(EASE_OUT).start()
    }

    // ---------- Reaksi ikon ----------
    fun icon(view: View, kind: Icon) {
        if (!enabled) return
        view.animate().cancel()
        val f = full
        when (kind) {
            Icon.TAP -> view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(d(70L))
                .withEndAction { back(view, 1f, 1f, 0f) }.start()
            Icon.SUCCESS -> { view.scaleX = 0.6f; view.scaleY = 0.6f
                view.animate().scaleX(1f).scaleY(1f).setDuration(d(MOTION_NORMAL))
                    .setInterpolator(if (f) SPRING_SOFT else EASE_OUT).start() }
            Icon.REFRESH -> view.animate().rotationBy(360f).setDuration(d(360L))
                .setInterpolator(EASE_IN_OUT).withEndAction { view.rotation = 0f }.start()
            Icon.FAVORITE -> view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(d(90L))
                .withEndAction { back(view, 1f, 1f, 0f) }.start()
            Icon.DELETE -> if (f) shakeX(view, 4f) else tap(view, 0.92f)
            Icon.DOWNLOAD -> nudge(view, dp(view, 5f))
            Icon.UPLOAD -> nudge(view, -dp(view, 5f))
            Icon.SEARCH -> view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(d(90L))
                .withEndAction { back(view, 1f, 1f, 0f) }.start()
            Icon.SETTINGS -> view.animate().rotationBy(if (f) 60f else 0f).setDuration(d(MOTION_NORMAL))
                .setInterpolator(EASE_IN_OUT).withEndAction { view.rotation = 0f }.start()
        }
    }

    private fun back(v: View, sx: Float, sy: Float, rot: Float) {
        v.animate().scaleX(sx).scaleY(sy).rotation(rot).setDuration(d(MOTION_NORMAL))
            .setInterpolator(if (full) SPRING_SOFT else EASE_OUT).start()
    }

    private fun nudge(v: View, dy: Float) {
        v.animate().translationY(dy).setDuration(d(90L)).setInterpolator(EASE_OUT)
            .withEndAction { v.animate().translationY(0f).setDuration(d(MOTION_NORMAL)).setInterpolator(EASE_OUT).start() }
            .start()
    }

    /** Reaksi ikon sesuai karakter Tool saat Tool Card disentuh. */
    fun characterIcon(view: View, character: Character) {
        if (!enabled) return
        view.animate().cancel()
        when (character) {
            Character.DIGITAL -> view.animate().scaleX(1.14f).scaleY(1.14f).setDuration(d(90L))
                .withEndAction { back(view, 1f, 1f, 0f) }.start()
            Character.STRUCTURED -> view.animate().translationY(-dp(view, 2f)).setDuration(d(90L))
                .withEndAction { view.animate().translationY(0f).setDuration(d(MOTION_NORMAL)).setInterpolator(EASE_OUT).start() }.start()
            Character.TECHNICAL -> view.animate().translationX(dp(view, 3f)).setDuration(d(80L))
                .withEndAction { view.animate().translationX(0f).setDuration(d(MOTION_NORMAL)).setInterpolator(EASE_OUT).start() }.start()
            Character.PROCESS -> if (full) view.animate().rotationBy(30f).setDuration(d(MOTION_NORMAL))
                .setInterpolator(EASE_IN_OUT).withEndAction { view.rotation = 0f }.start()
            Character.VISUAL -> view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(d(110L))
                .withEndAction { back(view, 1f, 1f, 0f) }.start()
            Character.CALM, Character.DEFAULT -> Unit
        }
    }

    // ---------- Success / Error / Warning ----------
    /** Pop kecil untuk sukses; panggil pada ikon/teks status. */
    fun success(view: View) { view.alpha = 1f; icon(view, Icon.SUCCESS) }

    /** Error: shake ringan hanya pada area bermasalah (Full). Reduced/Off: kedip alpha singkat. */
    fun error(view: View) {
        view.animate().cancel()
        view.translationX = 0f
        when (mode) {
            Mode.HIGH -> shakeX(view, 6f)
            Mode.MID -> view.animate().alpha(0.5f).setDuration(d(80L))
                .withEndAction { view.animate().alpha(1f).setDuration(d(120L)).start() }.start()
            Mode.LOW -> view.animate().alpha(0.65f).setDuration(d(70L))
                .withEndAction { view.animate().alpha(1f).setDuration(d(90L)).start() }.start()
            Mode.OFF -> view.alpha = 1f
        }
    }

    /** Warning: highlight lembut (fade turun-naik). */
    fun warn(view: View) {
        if (!enabled) return
        view.animate().cancel()
        view.animate().alpha(0.55f).setDuration(d(100L))
            .withEndAction { view.animate().alpha(1f).setDuration(d(MOTION_NORMAL)).start() }.start()
    }

    private fun shakeX(v: View, amp: Float) {
        val a = dp(v, amp)
        v.animate().translationX(a).setDuration(45L).withEndAction {
            v.animate().translationX(-a * 0.7f).setDuration(55L).withEndAction {
                v.animate().translationX(0f).setDuration(45L).start()
            }.start()
        }.start()
    }

    // ---------- Status teks & loading ----------
    /** Ganti teks status dengan crossfade singkat (Preparing… → Processing… → Completed). */
    fun swapText(tv: TextView, newText: CharSequence) {
        if (tv.text.toString() == newText.toString()) return
        if (!enabled) { tv.text = newText; return }
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(d(70L)).withEndAction {
            tv.text = newText
            tv.animate().alpha(1f).setDuration(d(MOTION_FAST)).setInterpolator(EASE_OUT).start()
        }.start()
    }

    private val spinners = WeakHashMap<View, android.animation.Animator>()

    /**
     * Loading ringan: ikon berputar. Otomatis berhenti saat view lepas dari window
     * atau tidak terlihat → tidak ada infinite animation yatim / memory leak.
     * Di mode Off memakai pulse alpha statis (tanpa animator berulang).
     */
    fun startLoading(view: View) {
        stopLoading(view)
        when (mode) {
            Mode.OFF -> { view.alpha = 0.6f; return }
            Mode.LOW -> {
                val anim = ObjectAnimator.ofFloat(view, View.ALPHA, 0.45f, 1f).apply {
                    duration = 720L
                    interpolator = EASE_IN_OUT
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
                spinners[view] = anim
                attachLoadingCleanup(view, anim)
                anim.start()
                return
            }
            Mode.MID -> {
                val anim = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
                    duration = MOTION_LOADING
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                }
                spinners[view] = anim
                attachLoadingCleanup(view, anim)
                anim.start()
                return
            }
            Mode.HIGH -> {
                // High: rotation + breathing scale, tetap ringan karena hanya View properties.
                val rotate = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
                    duration = 760L
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                }
                val pulse = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.88f, 1.08f).apply {
                    duration = 540L
                    interpolator = EASE_IN_OUT
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
                val pulseY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.88f, 1.08f).apply {
                    duration = 540L
                    interpolator = EASE_IN_OUT
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
                val set = AnimatorSet().apply { playTogether(rotate, pulse, pulseY) }
                spinners[view] = set
                attachLoadingCleanup(view, set)
                set.start()
            }
        }
    }

    private val spinnerListeners = WeakHashMap<View, View.OnAttachStateChangeListener>()

    private fun attachLoadingCleanup(view: View, animator: android.animation.Animator) {
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                animator.cancel()
                spinners.remove(v)
                spinnerListeners.remove(v)
                v.removeOnAttachStateChangeListener(this)
            }
        }
        spinnerListeners[view] = listener
        view.addOnAttachStateChangeListener(listener)
    }

    fun stopLoading(view: View) {
        spinners.remove(view)?.cancel()
        // Lepas listener lama agar tidak menumpuk setiap kali loading dimulai ulang.
        spinnerListeners.remove(view)?.let { view.removeOnAttachStateChangeListener(it) }
        view.rotation = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
    }

    /** Hentikan semua animasi sebuah view (panggil saat pindah halaman / onDestroy). */
    fun cancel(view: View) { stopLoading(view); settle(view) }

    // ---------- Karakter per Tool ----------
    fun characterFor(toolId: String): Character = when (toolId) {
        "qr", "ocr", "hash", "checksum", "uuid", "uuidbatch", "token", "password", "base64", "base32", "hex" -> Character.DIGITAL
        "filemanager", "recentfiles", "zip", "githubzip", "backuprestore", "workspace", "duplicatefinder",
        "largefilefinder", "apkcompare" -> Character.STRUCTURED
        "editor", "json", "jsonformat", "xmlformat", "yaml", "toml", "sql", "regex", "http", "webhostwifi",
        "dns", "rdns", "port", "ping", "ssl", "publicip", "ipinfo", "cron" -> Character.TECHNICAL
        "apk", "system", "systemcenter", "devicecenter", "studiocenter", "plugincenter", "wifi" -> Character.PROCESS
        "reminder", "customtools", "history", "helpbot", "number", "unitconverter" -> Character.CALM
        "color", "uicolorcalc" -> Character.VISUAL
        else -> Character.DEFAULT
    }
}
