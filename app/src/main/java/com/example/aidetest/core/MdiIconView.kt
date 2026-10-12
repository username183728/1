package com.example.aidetest

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class MdiIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        private const val FONT = "fonts/materialdesignicons-webfont.ttf"
        private val TYPEFACE_CACHE = mutableMapOf<String, Typeface>()
    }

    private var glyphName: String = ""

    init {
        typeface = mdiTypeface(context)
        gravity = android.view.Gravity.CENTER
        includeFontPadding = false
        setTextIsSelectable(false)
    }

    fun setIconName(name: String) {
        glyphName = name
        text = MdiGlyphs.glyph(name)
    }

    fun setIconSize(sizeSp: Float) {
        textSize = sizeSp
    }

    private fun mdiTypeface(context: Context): Typeface {
        return TYPEFACE_CACHE.getOrPut(FONT) {
            Typeface.createFromAsset(context.assets, FONT)
        }
    }
}
