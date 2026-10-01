package com.example.aidetest

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UI Kit bersama untuk semua tool.
 * Pakai komponen ini supaya tampilan konsisten: empty state, result card,
 * primary button, status badge, section header, dll.
 *
 * Semua function adalah extension MainActivity agar bisa akses
 * theme tokens (panel, textMain, isDarkTheme, dp, bg, …).
 */
internal fun MainActivity.uiEmptyState(
    title: String,
    detail: String,
    icon: String = "inbox-outline",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(Ds.SPACE_XL), dp(Ds.SPACE_XXL), dp(Ds.SPACE_XL), dp(Ds.SPACE_XXL))
        background = bg(panel2, Ds.RADIUS_LG, line)
    }
    val iconWrap = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = bg(Ds.statusSoftBg(Ds.State.EMPTY, isDarkTheme), Ds.RADIUS_MD)
    }
    iconWrap.addView(MdiIconView(this).apply {
        setIconName(icon)
        setIconSize(Ds.ICON_LG)
        setTextColor(Ds.statusColor(Ds.State.EMPTY, isDarkTheme))
    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
        gravity = Gravity.CENTER
    })
    box.addView(iconWrap, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        bottomMargin = dp(Ds.SPACE_MD)
    })
    box.addView(TextView(this).apply {
        text = title
        textSize = Ds.TEXT_SUBTITLE
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
        gravity = Gravity.CENTER
    })
    box.addView(TextView(this).apply {
        text = detail
        textSize = Ds.TEXT_CAPTION_MIN
        setTextColor(textMuted)
        gravity = Gravity.CENTER
        setPadding(dp(Ds.SPACE_SM), dp(Ds.SPACE_XS), dp(Ds.SPACE_SM), 0)
    })
    if (actionLabel != null && onAction != null) {
        val btn = TextView(this).apply {
            text = actionLabel
            textSize = Ds.TEXT_BODY
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
            gravity = Gravity.CENTER
            minHeight = dp(Ds.TOUCH_MIN)
            setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_SM), dp(Ds.SPACE_LG), dp(Ds.SPACE_SM))
            background = rippleBg(if (isDarkTheme) Color.rgb(40, 40, 44) else Color.rgb(230, 232, 236), Ds.RADIUS_MD)
            setOnClickListener { onAction() }
        }
        box.addView(btn, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(Ds.SPACE_LG)
        })
    }
    return box
}

internal fun MainActivity.uiStatusBadge(text: String, state: Ds.State): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Ds.SPACE_SM), dp(Ds.SPACE_XS), dp(Ds.SPACE_SM), dp(Ds.SPACE_XS))
        background = bg(Ds.statusSoftBg(state, isDarkTheme), Ds.RADIUS_PILL)
    }
    row.addView(MdiIconView(this).apply {
        setIconName(Ds.stateIcon(state))
        setIconSize(14f)
        setTextColor(Ds.statusColor(state, isDarkTheme))
    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(4) })
    row.addView(TextView(this).apply {
        this.text = text
        textSize = Ds.TEXT_CAPTION
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ds.statusColor(state, isDarkTheme))
    })
    return row
}

internal fun MainActivity.uiResultCard(
    title: String,
    body: String,
    state: Ds.State = Ds.State.INFO
): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        background = bg(panel2, Ds.RADIUS_LG, line)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    top.addView(uiStatusBadge(title, state))
    box.addView(top)
    box.addView(TextView(this).apply {
        text = body
        textSize = Ds.TEXT_BODY
        setTextColor(textMain)
        setPadding(0, dp(Ds.SPACE_SM), 0, 0)
        setTextIsSelectable(true)
    })
    return box
}

internal fun MainActivity.uiPrimaryButton(text: String, onClick: () -> Unit): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = Ds.TEXT_BODY
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (isDarkTheme) Color.BLACK else Color.WHITE)
        gravity = Gravity.CENTER
        minHeight = dp(Ds.TOUCH_MIN)
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        val fill = if (isDarkTheme) Color.rgb(245, 245, 247) else Color.rgb(17, 17, 19)
        background = rippleBg(fill, Ds.RADIUS_MD)
        setOnClickListener { onClick() }
    }
}

internal fun MainActivity.uiSecondaryButton(text: String, onClick: () -> Unit): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = Ds.TEXT_BODY
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
        gravity = Gravity.CENTER
        minHeight = dp(Ds.TOUCH_MIN)
        setPadding(dp(Ds.SPACE_LG), dp(Ds.SPACE_MD), dp(Ds.SPACE_LG), dp(Ds.SPACE_MD))
        background = rippleBg(panel2, Ds.RADIUS_MD, line)
        setOnClickListener { onClick() }
    }
}

internal fun MainActivity.uiSectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(Ds.SPACE_MD), dp(2), dp(Ds.SPACE_SM))
    }
    row.addView(TextView(this).apply {
        text = title
        textSize = Ds.TEXT_SUBTITLE
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textMain)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    if (action != null && onAction != null) {
        row.addView(TextView(this).apply {
            text = action
            textSize = Ds.TEXT_CAPTION_MIN
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ds.statusColor(Ds.State.INFO, isDarkTheme))
            setPadding(dp(Ds.SPACE_SM), dp(Ds.SPACE_XS), dp(Ds.SPACE_SM), dp(Ds.SPACE_XS))
            background = rippleBg(Ds.statusSoftBg(Ds.State.INFO, isDarkTheme), Ds.RADIUS_SM)
            setOnClickListener { onAction() }
        })
    }
    return row
}

internal fun MainActivity.uiMetricTile(
    title: String,
    value: String,
    icon: String,
    accent: Int? = null
): View {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD), dp(Ds.SPACE_MD))
        background = bg(panel2, Ds.RADIUS_LG, line)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val iconBg = accent?.let { Color.argb(28, Color.red(it), Color.green(it), Color.blue(it)) } ?: panel
    top.addView(MdiIconView(this).apply {
        setIconName(icon)
        setIconSize(Ds.ICON_SM)
        setTextColor(accent ?: textMain)
        background = bg(iconBg, Ds.RADIUS_SM)
    }, LinearLayout.LayoutParams(dp(32), dp(32)))
    top.addView(TextView(this).apply {
        text = title
        textSize = Ds.TEXT_CAPTION
        setTextColor(textMuted)
        setPadding(dp(Ds.SPACE_SM), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    box.addView(top)
    box.addView(TextView(this).apply {
        text = value
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent ?: textMain)
        setPadding(0, dp(Ds.SPACE_SM), 0, 0)
    })
    return box
}

internal fun MainActivity.uiProgressBar(ratio: Float, color: Int? = null, heightDp: Int = 8): View {
    val r = ratio.coerceIn(0.02f, 1f)
    val track = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = bg(panel2, Ds.RADIUS_PILL)
    }
    val fillColor = color ?: textMain
    track.addView(View(this).apply {
        background = bg(fillColor, Ds.RADIUS_PILL)
    }, LinearLayout.LayoutParams(0, dp(heightDp), r))
    track.addView(View(this), LinearLayout.LayoutParams(0, dp(heightDp), 1f - r))
    return track
}

internal fun MainActivity.uiInfoNote(text: String): View {
    return TextView(this).apply {
        this.text = text
        textSize = Ds.TEXT_CAPTION
        setTextColor(textMuted)
        setPadding(dp(Ds.SPACE_SM), dp(Ds.SPACE_MD), dp(Ds.SPACE_SM), dp(Ds.SPACE_LG))
    }
}
