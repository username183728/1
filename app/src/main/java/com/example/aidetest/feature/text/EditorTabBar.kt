package com.example.aidetest

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Multi-file tab bar (VS Code style) for the mobile editor.
 * Separate from the HTML/CSS/JS mode switcher already in the editor.
 */
object EditorTabBar {

    data class Tab(
        val key: String,
        val label: String,
        val file: File?,
        val mode: String,
        val sourceUri: String? = null
    )

    fun build(
        activity: MainActivity,
        tabs: List<Tab>,
        activeKey: String,
        onSelect: (Tab) -> Unit,
        onClose: (Tab) -> Unit
    ): View {
        val dark = activity.isDarkTheme
        val bg = if (dark) Color.rgb(22, 22, 25) else Color.rgb(240, 241, 243)
        val activeBg = if (dark) Color.rgb(40, 42, 48) else Color.WHITE
        val textColor = if (dark) Color.rgb(220, 220, 220) else Color.rgb(40, 40, 40)
        val muted = if (dark) Color.rgb(140, 140, 150) else Color.rgb(120, 120, 130)
        val border = if (dark) Color.rgb(55, 55, 60) else Color.rgb(210, 212, 218)

        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(bg)
            setPadding(activity.dp(4), activity.dp(1), activity.dp(4), activity.dp(1))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(28)
        }

        val accent = Ds.statusColor(Ds.State.INFO, dark)
        var activeWrap: View? = null

        if (tabs.isEmpty()) {
            row.addView(TextView(activity).apply {
                text = "Tidak ada tab terbuka"
                textSize = 11f
                setTextColor(muted)
                setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
            })
        } else {
            tabs.forEach { tab ->
                val isActive = tab.key == activeKey
                val cellBg = GradientDrawable().apply {
                    setColor(if (isActive) activeBg else Color.TRANSPARENT)
                    cornerRadius = activity.dp(8).toFloat()
                    if (isActive) setStroke(activity.dp(1), border)
                }
                // Tab dibuat ringkas (30dp) agar tidak memakan layar.
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = cellBg
                    minimumHeight = activity.dp(30)
                    setPadding(activity.dp(10), activity.dp(2), activity.dp(0), activity.dp(2))
                    isClickable = true
                    contentDescription = if (isActive) "Tab ${tab.label}, aktif" else "Buka tab ${tab.label}"
                    setOnClickListener { onSelect(tab) }
                }
                // Indikator dirty: titik di depan nama bila buffer belum disimpan.
                val isDirty = activity.editorBuffers[tab.key]?.dirty == true
                cell.addView(TextView(activity).apply {
                    text = if (isDirty) "• ${tab.label}" else tab.label
                    textSize = 12f
                    typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(
                        when {
                            isDirty && isActive -> Color.rgb(230, 126, 34)
                            isDirty -> Color.rgb(200, 110, 30)
                            isActive -> textColor
                            else -> muted
                        }
                    )
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxWidth = activity.dp(140)
                    contentDescription = if (isDirty) "${tab.label}, belum disimpan" else tab.label
                })
                // Area tutup 30dp.
                cell.addView(MdiIconView(activity).apply {
                    setIconName("close")
                    setIconSize(13f)
                    gravity = Gravity.CENTER
                    setTextColor(muted)
                    minWidth = activity.dp(30)
                    minHeight = activity.dp(30)
                    contentDescription = "Tutup tab ${tab.label}"
                    isClickable = true
                    setOnClickListener { onClose(tab) }
                })
                Motion.press(cell, scale = 0.97f)

                // Garis aksen di bawah tab aktif: penanda posisi yang jelas di Light maupun Dark.
                val indicator = View(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(if (isActive) accent else Color.TRANSPARENT)
                        cornerRadius = activity.dp(2).toFloat()
                    }
                }
                val wrap = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        rightMargin = activity.dp(4)
                    }
                }
                wrap.addView(cell, LinearLayout.LayoutParams(-2, -2))
                wrap.addView(indicator, LinearLayout.LayoutParams(-1, activity.dp(2)).apply {
                    leftMargin = activity.dp(10)
                    rightMargin = activity.dp(10)
                    topMargin = 0
                })
                if (isActive) activeWrap = wrap
                row.addView(wrap)
            }
        }
        scroll.addView(row)
        // Tab aktif selalu terlihat, termasuk saat banyak tab terbuka.
        activeWrap?.let { target ->
            scroll.post {
                scroll.smoothScrollTo((target.left - activity.dp(24)).coerceAtLeast(0), 0)
            }
        }
        return scroll
    }
}
