package com.example.aidetest

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ganti tema (Terang/Gelap/Sistem) = muat ulang seluruh UI seperti baru membuka app,
 * tetapi posisi halaman, tool yang sedang dibuka, isi editor (termasuk yang belum disimpan),
 * isi kolom input, dan posisi scroll dipulihkan otomatis agar pekerjaan tidak hilang.
 */
private const val SESSION_FILE = "theme_restart_session.json"
private const val SESSION_MAX_AGE_MS = 20_000L
private val ROOT_PAGES = setOf("home", "all", "favorites", "settings")

private fun MainActivity.sessionFile() = File(cacheDir, SESSION_FILE)

private fun isPasswordField(e: EditText): Boolean {
    val v = e.inputType and InputType.TYPE_MASK_VARIATION
    return v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
        v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
        v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
        v == InputType.TYPE_NUMBER_VARIATION_PASSWORD
}

private fun collectEditTexts(root: View, out: MutableList<EditText>) {
    if (root is EditText) { out.add(root); return }
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) collectEditTexts(root.getChildAt(i), out)
    }
}

/** Simpan keadaan layar saat ini ke cache (tidak ke prefs, karena isi editor bisa besar). */
internal fun MainActivity.saveThemeRestartSession() {
    val json = JSONObject()
    json.put("time", System.currentTimeMillis())
    json.put("page", currentPage)
    json.put("scrollY", runCatching { scroll.scrollY }.getOrDefault(0))

    val box = editorBox
    if (currentPage == "Editor" && box != null && box.isAttachedToWindow) {
        val ed = JSONObject()
        ed.put("text", box.text?.toString().orEmpty())
        ed.put("selStart", box.selectionStart.coerceAtLeast(0))
        ed.put("selEnd", box.selectionEnd.coerceAtLeast(0))
        ed.put("mode", editorMode)
        ed.put("filePath", editorFile?.absolutePath ?: "")
        ed.put("sourceUri", editorSourceUri?.toString() ?: "")
        ed.put("scrollY", box.scrollY)
        json.put("editor", ed)
    } else if (currentPage !in ROOT_PAGES && currentPage == lastOpenedToolPage && lastOpenedToolId != null) {
        json.put("toolId", lastOpenedToolId)
    }

    // Isi kolom input biasa (kecuali kolom password) berdasarkan urutan di layar.
    val fields = mutableListOf<EditText>()
    collectEditTexts(content, fields)
    val texts = JSONArray()
    fields.filter { it !== box }.forEach { e ->
        texts.put(if (isPasswordField(e)) "" else e.text?.toString().orEmpty())
    }
    json.put("fields", texts)

    sessionFile().writeText(json.toString())
}

/** Pulihkan sesi setelah Activity dibuat ulang. Aman dipanggil kapan pun; abaikan bila basi. */
internal fun MainActivity.restoreThemeRestartSession() {
    val f = sessionFile()
    if (!f.exists()) return
    val json = runCatching { JSONObject(f.readText()) }.getOrNull()
    runCatching { f.delete() }
    json ?: return
    if (System.currentTimeMillis() - json.optLong("time") > SESSION_MAX_AGE_MS) return

    val page = json.optString("page", "home")
    val scrollY = json.optInt("scrollY", 0)
    val editorJson = json.optJSONObject("editor")
    val toolId = json.optString("toolId", "").ifEmpty { null }

    when {
        editorJson != null -> {
            val path = editorJson.optString("filePath")
            val file = path.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
            val mode = editorJson.optString("mode", "text")
            if (file != null) editor(file) else editor(null, mode)
            editorJson.optString("sourceUri").takeIf { it.isNotEmpty() }?.let {
                editorSourceUri = android.net.Uri.parse(it)
            }
            content.postDelayed({
                val box = editorBox ?: return@postDelayed
                val text = editorJson.optString("text")
                if (box.text?.toString() != text) box.setText(text)
                val len = box.text?.length ?: 0
                val s = editorJson.optInt("selStart", 0).coerceIn(0, len)
                val e = editorJson.optInt("selEnd", s).coerceIn(0, len)
                runCatching { box.setSelection(s, e) }
                box.scrollTo(0, editorJson.optInt("scrollY", 0))
            }, 220L)
        }
        toolId != null -> runCatching { openTool(toolId) }
        page == "all" -> findViewById<View>(R.id.navTools)?.performClick()
        page == "favorites" -> findViewById<View>(R.id.navFavorite)?.performClick()
        page == "settings" -> showSettings()
        // "home" atau halaman yang tidak bisa dibuka ulang otomatis: tetap di Beranda.
    }

    // Kembalikan isi kolom input + posisi scroll setelah halaman selesai dibangun.
    val saved = json.optJSONArray("fields")
    content.postDelayed({
        if (saved != null && saved.length() > 0) {
            val fields = mutableListOf<EditText>()
            collectEditTexts(content, fields)
            val targets = fields.filter { it !== editorBox }
            if (targets.size == saved.length()) {
                targets.forEachIndexed { i, e ->
                    val t = saved.optString(i, "")
                    if (t.isNotEmpty() && !isPasswordField(e) && e.text?.toString() != t) e.setText(t)
                }
            }
        }
        if (editorJson == null) scroll.scrollTo(0, scrollY)
    }, 320L)
}

/** Dipanggil setelah pengguna memilih tema baru: simpan sesi lalu muat ulang UI dari awal. */
internal fun MainActivity.restartForThemeChange() {
    runCatching { saveThemeRestartSession() }
        .onFailure { android.util.Log.w("GITLS", "Theme session save skipped", it) }
    recreate()
}
