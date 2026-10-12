package com.example.aidetest

import android.app.Dialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Command Palette — pencarian cepat semua tool (Ctrl/Cmd-K style di mobile).
 * Membuka dialog fullscreen-ish dengan filter live + kategori + recent.
 */
internal fun MainActivity.showCommandPalette() {
    val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(if (isDarkTheme) Color.rgb(12, 13, 16) else Color.rgb(248, 249, 251))
        setPadding(dp(16), sysTopInset + dp(12), dp(16), dp(16))
    }

    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(TextView(this).apply {
        text = "Command Palette"
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textMain)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(TextView(this).apply {
        text = "Tutup"
        textSize = 14f
        setTextColor(textMuted)
        setPadding(dp(12), dp(8), dp(4), dp(8))
        isClickable = true
        setOnClickListener { dialog.dismiss() }
    })
    root.addView(header)

    root.addView(TextView(this).apply {
        text = "Ketik nama tool, kategori, atau aksi — Enter membuka hasil teratas."
        textSize = 12f
        setTextColor(textMuted)
        setPadding(0, dp(4), 0, dp(10))
    })

    val searchBox = EditText(this).apply {
        hint = "Cari tools…  json, ping, hash, editor"
        textSize = 16f
        setTextColor(textMain)
        setHintTextColor(textMuted)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = bg(if (isDarkTheme) Color.rgb(28, 30, 36) else Color.WHITE, 16, line)
        isSingleLine = true
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
    }
    root.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

    val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(listHost)
    }
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

    fun score(query: String, id: String, name: String): Int {
        if (query.isBlank()) return 1
        val q = query.lowercase()
        val n = name.lowercase()
        val i = id.lowercase()
        return when {
            i == q || n == q -> 100
            i.startsWith(q) || n.startsWith(q) -> 80
            i.contains(q) || n.contains(q) -> 50
            q.split(" ").all { it.isBlank() || n.contains(it) || i.contains(it) } -> 30
            else -> 0
        }
    }

    fun render(query: String) {
        listHost.removeAllViews()
        val recent = recentToolIds().take(6)
        if (query.isBlank() && recent.isNotEmpty()) {
            listHost.addView(TextView(this).apply {
                text = "TERAKHIR"
                textSize = 11f
                setTextColor(textMuted)
                setPadding(dp(4), dp(4), dp(4), dp(6))
                letterSpacing = 0.06f
            })
            recent.forEach { id ->
                val name = homeToolMap[id] ?: return@forEach
                listHost.addView(paletteRow(id, name, "Recent") {
                    dialog.dismiss()
                    openTool(id)
                })
            }
        }
        val ranked = homeTools.map { (id, name) -> Triple(id, name, score(query, id, name)) }
            .filter { it.third > 0 }
            .sortedByDescending { it.third }
            .take(40)
        if (ranked.isEmpty()) {
            listHost.addView(TextView(this).apply {
                text = "Tidak ada tool yang cocok"
                textSize = 14f
                setTextColor(textMuted)
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }
        if (query.isNotBlank()) {
            listHost.addView(TextView(this).apply {
                text = "HASIL (${ranked.size})"
                textSize = 11f
                setTextColor(textMuted)
                setPadding(dp(4), dp(8), dp(4), dp(6))
                letterSpacing = 0.06f
            })
        } else if (recent.isEmpty()) {
            listHost.addView(TextView(this).apply {
                text = "SEMUA TOOLS"
                textSize = 11f
                setTextColor(textMuted)
                setPadding(dp(4), dp(4), dp(4), dp(6))
            })
        } else {
            listHost.addView(TextView(this).apply {
                text = "SARAN"
                textSize = 11f
                setTextColor(textMuted)
                setPadding(dp(4), dp(12), dp(4), dp(6))
                letterSpacing = 0.06f
            })
        }
        ranked.forEach { (id, name, _) ->
            listHost.addView(paletteRow(id, name, categoryLabelFor(id)) {
                dialog.dismiss()
                openTool(id)
            })
        }
    }

    searchBox.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { render(s?.toString().orEmpty()) }
        override fun afterTextChanged(s: Editable?) {}
    })
    searchBox.setOnEditorActionListener { _, _, _ ->
        val q = searchBox.text.toString()
        val top = homeTools.map { (id, name) -> Triple(id, name, score(q, id, name)) }
            .filter { it.third > 0 }
            .maxByOrNull { it.third }
        if (top != null) {
            dialog.dismiss()
            openTool(top.first)
            true
        } else false
    }

    dialog.setContentView(root)
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.show()
    render("")
    searchBox.post { searchBox.requestFocus() }
}

private fun MainActivity.paletteRow(id: String, name: String, meta: String, onClick: () -> Unit): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rippleBg(
            if (isDarkTheme) Color.rgb(22, 24, 28) else Color.WHITE,
            14, line
        )
        isClickable = true
        isFocusable = true
        contentDescription = name
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
    }
    row.addView(MdiIconView(this).apply {
        setIconName(iconFor(id))
        setIconSize(20f)
        setTextColor(textMain)
    }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) })
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(this).apply {
        text = name
        textSize = 15f
        setTextColor(textMain)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    })
    texts.addView(TextView(this).apply {
        text = meta
        textSize = 11f
        setTextColor(textMuted)
    })
    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
    if (isFavorite(id)) {
        row.addView(TextView(this).apply {
            text = "★"
            textSize = 14f
            setTextColor(Color.rgb(240, 180, 40))
        })
    }
    return row
}

private fun MainActivity.categoryLabelFor(id: String): String {
    val n = (homeToolMap[id] ?: id).lowercase()
    return when {
        id in setOf("filemanager", "zip", "recentfiles", "backuprestore", "filesearch") || n.contains("file") -> "File"
        id in setOf("editor", "json", "jsonformat", "regex", "markdown", "yaml") || n.contains("json") || n.contains("editor") -> "Developer"
        id in setOf("ping", "dns", "networkcenter", "wifi", "port", "traceroute") || n.contains("network") || n.contains("wifi") -> "Network"
        id in setOf("hash", "aes", "password", "securitycenter", "jwt") || n.contains("security") || n.contains("hash") -> "Security"
        id.endsWith("calc") || id == "number" || n.contains("calc") -> "Calculator"
        id.startsWith("finance") || n.contains("finance") -> "Finance"
        else -> "Tools"
    }
}

private fun MainActivity.recentToolIds(): List<String> {
    val raw = prefs.getString("recent_tools", "") ?: ""
    return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && homeToolMap.containsKey(it) }
}
