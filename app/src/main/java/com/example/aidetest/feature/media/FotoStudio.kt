package com.example.aidetest

import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Foto Studio — satu halaman untuk Desain (foto · desain · hapus latar) dan Animasi (keyframe · MP4/GIF).
 * Ganti mode lewat tab di atas tanpa pindah halaman; state proyek ada di CsProject / AnProject (singleton),
 * jadi UI mode lama cukup dilepas (autosave jalan lewat detach listener) dan UI mode baru dibuat.
 */
internal object FotoStudioState {
    const val DESAIN = "desain"
    const val ANIMASI = "animasi"
    var mode = DESAIN
}

internal class FotoStudioHost(private val a: MainActivity, private var entry: CsToolIds.Entry) {
    private val c = EditorChrome(a)
    val root = LinearLayout(a)
    private val tabs = LinearLayout(a)
    private val body = FrameLayout(a)
    private var current: String? = null

    private fun dp(v: Int) = a.dp(v)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(c.surface)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.background = c.shape(c.surface2, 14)
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3))
        val bar = FrameLayout(a).apply { setPadding(dp(12), dp(5), dp(12), dp(5)) }
        bar.addView(tabs, FrameLayout.LayoutParams(-1, -1))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        show(FotoStudioState.mode)
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        for ((key, label) in listOf(FotoStudioState.DESAIN to "Desain", FotoStudioState.ANIMASI to "Animasi")) {
            val active = key == FotoStudioState.mode
            tabs.addView(TextView(a).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (active) c.ink else c.muted)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = if (active) c.shape(c.surface, 11, c.stroke) else null
                isClickable = true
                contentDescription = "Mode $label"
                setOnClickListener { if (FotoStudioState.mode != key) show(key) }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun show(mode: String) {
        if (mode == FotoStudioState.ANIMASI && current == FotoStudioState.DESAIN) syncDesignToAnimation()
        current = mode
        FotoStudioState.mode = mode
        renderTabs()
        body.removeAllViews()
        val child = if (mode == FotoStudioState.ANIMASI) AnimStudioUi(a).root else CreativeStudioUi(a, entry).root
        entry = CsToolIds.Entry.DEFAULT
        body.addView(child, FrameLayout.LayoutParams(-1, -1))
    }

    /** Desain aktif jadi sumber animasi; keyframe layer yang masih ada dipertahankan. */
    private fun syncDesignToAnimation() {
        if (!CsProject.initialized || CsProject.elements.isEmpty()) return
        runCatching {
            AnStore.ensureLoaded(a)
            val doc = CsProject.doc()
            val ids = doc.elements.map { it.id }.toSet()
            val old = AnProject.doc
            if (old != null && AnProject.keyCount() > 0 && old.elements.none { it.id in ids }) {
                AnStore.stashIfNeeded(a)?.let { a.toast("Animasi sebelumnya disimpan: $it") }
            }
            val name = if (old != null) AnProject.name else CsProject.projectName
            AnProject.setDoc(doc, name, HashMap(AnProject.tracks), AnProject.durationMs, AnProject.file)
            AnStore.autosave(a)
        }
    }
}
