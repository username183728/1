package com.example.aidetest

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Stub: screen-capture color picker (MediaProjection + SYSTEM_ALERT_WINDOW) removed
 * for privacy and platform policy compliance. Constants kept so older broadcasts
 * do not break compile references in MainActivity.
 */
class ColorPickerService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "color_picker_result_code"
        const val EXTRA_RESULT_DATA = "color_picker_result_data"
        const val ACTION_COLOR_PICKED = "com.example.aidetest.COLOR_PICKED"
        const val EXTRA_COLOR = "color"
        const val EXTRA_HEX = "hex"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
