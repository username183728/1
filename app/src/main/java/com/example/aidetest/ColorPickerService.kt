package com.example.aidetest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ColorPickerService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "color_picker_result_code"
        const val EXTRA_RESULT_DATA = "color_picker_result_data"
        private const val CHANNEL_ID = "color_picker"
        private const val NOTIFICATION_ID = 7422
        const val ACTION_COLOR_PICKED = "com.example.aidetest.COLOR_PICKED"
        const val EXTRA_COLOR = "color"
        const val EXTRA_HEX = "hex"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var colorPreview: View? = null
    private var hexView: TextView? = null
    private var rgbView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var lastColor = Color.WHITE
    private var density = 1f
    private var screenWidth = 0
    private var screenHeight = 0
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var marker: View? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private val sampleHandler = Handler(Looper.getMainLooper())
    private var sampleAttempts = 0
    private var samplingInProgress = false

    override fun onCreate() {
        super.onCreate()
        density = resources.displayMetrics.density
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = (intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent) ?: return START_NOT_STICKY
        stopCaptureOnly()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        setupCapture()
        setupOverlay()
        return START_STICKY
    }

    private fun setupCapture() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val imageW = screenWidth.coerceAtLeast(1)
        val imageH = screenHeight.coerceAtLeast(1)
        imageReader = ImageReader.newInstance(imageW, imageH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "MyTools Color Picker", imageW, imageH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun setupOverlay() {
        val wm = windowManager ?: return
        removeOverlay()

        val bubbleSize = dp(54)
        bubble = TextView(this).apply {
            text = "●"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 35, 35, 38))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        bubbleParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 2 - bubbleSize / 2
            y = screenHeight / 2 - bubbleSize / 2
        }
        bubble?.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) moved = true
                    params.x = (startX + dx).coerceIn(0, max(0, screenWidth - bubbleSize))
                    params.y = (startY + dy).coerceIn(0, max(0, screenHeight - bubbleSize))
                    runCatching { wm.updateViewLayout(bubble, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) sampleAtBubble()
                    true
                }
                else -> true
            }
        }
        wm.addView(bubble, bubbleParams)

        // Non-touchable sampling marker: remains exactly at the last sampled point.
        marker = View(this).apply {
            background = markerDrawable(lastColor)
            elevation = dp(12).toFloat()
        }
        markerParams = WindowManager.LayoutParams(
            dp(20), dp(20), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = -dp(100); y = -dp(100) }
        wm.addView(marker, markerParams)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(Color.rgb(31,31,33)); cornerRadius = dp(16).toFloat() }
            elevation = dp(10).toFloat()
        }
        colorPreview = View(this).apply { background = solid(lastColor, 10) }
        hexView = TextView(this).apply { text = "#FFFFFF"; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0) }
        rgbView = TextView(this).apply { text = "RGB 255, 255, 255"; textSize = 12f; setTextColor(Color.LTGRAY) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copy = TextView(this).apply {
            text = "SALIN"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = solid(Color.rgb(55,55,58), 12)
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("HEX", hexView?.text?.toString() ?: "#FFFFFF"))
                android.widget.Toast.makeText(this@ColorPickerService, "HEX disalin", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val stop = TextView(this).apply {
            text = "MATIKAN"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = solid(Color.rgb(75,55,58), 12)
            setOnClickListener { stopSelf() }
        }
        actions.addView(copy, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        actions.addView(stop, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(4) })
        panel?.addView(colorPreview, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel?.addView(hexView)
        panel?.addView(rgbView)
        panel?.addView(actions, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
        val panelParams = WindowManager.LayoutParams(
            dp(190), dp(142), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(55) }
        wm.addView(panel, panelParams)
    }

    private fun sampleAtBubble() {
        if (samplingInProgress) return
        val params = bubbleParams ?: return
        val sampleX = params.x + params.width / 2
        val sampleY = params.y + params.height / 2
        samplingInProgress = true
        sampleAttempts = 0

        // The old implementation sampled the center while the picker bubble itself
        // was covering that pixel. Temporarily hide every overlay, let MediaProjection
        // produce a fresh frame, then sample the underlying screen.
        bubble?.visibility = View.INVISIBLE
        marker?.visibility = View.INVISIBLE
        panel?.visibility = View.INVISIBLE
        sampleHandler.postDelayed({ sampleUnderlyingPixel(sampleX, sampleY) }, 90L)
    }

    private fun restoreOverlayAfterSampling() {
        bubble?.visibility = View.VISIBLE
        marker?.visibility = View.VISIBLE
        panel?.visibility = View.VISIBLE
        samplingInProgress = false
    }

    private fun sampleUnderlyingPixel(screenX: Int, screenY: Int) {
        val image = runCatching { imageReader?.acquireLatestImage() }.getOrNull()
        if (image == null) {
            if (sampleAttempts++ < 8) {
                sampleHandler.postDelayed({ sampleUnderlyingPixel(screenX, screenY) }, 55L)
            } else {
                restoreOverlayAfterSampling()
            }
            return
        }
        try {
            val plane = image.planes.firstOrNull() ?: throw IllegalStateException("No image plane")
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride.coerceAtLeast(pixelStride * image.width)
            val rowPadding = rowStride - pixelStride * image.width
            val bitmapWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val cx = screenX.coerceIn(1, bitmap.width - 2)
            val cy = screenY.coerceIn(1, bitmap.height - 2)
            var sr = 0L
            var sg = 0L
            var sb = 0L
            var count = 0
            for (yy in -1..1) for (xx in -1..1) {
                val c = bitmap.getPixel(cx + xx, cy + yy)
                sr += Color.red(c)
                sg += Color.green(c)
                sb += Color.blue(c)
                count++
            }
            bitmap.recycle()
            setColor(Color.rgb((sr / count).toInt(), (sg / count).toInt(), (sb / count).toInt()), screenX, screenY)
            restoreOverlayAfterSampling()
        } catch (_: Throwable) {
            if (sampleAttempts++ < 3) {
                sampleHandler.postDelayed({ sampleUnderlyingPixel(screenX, screenY) }, 55L)
            } else {
                restoreOverlayAfterSampling()
            }
        } finally {
            image.close()
        }
    }

    private fun setColor(color: Int, sampleX: Int? = null, sampleY: Int? = null) {
        lastColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        val r = Color.red(lastColor); val g = Color.green(lastColor); val b = Color.blue(lastColor)
        val hex = "#%02X%02X%02X".format(Locale.US, r, g, b)
        colorPreview?.setBackgroundColor(lastColor)
        hexView?.text = hex
        rgbView?.text = "RGB $r, $g, $b"
        bubble?.background = markerDrawable(lastColor, 2)
        marker?.background = markerDrawable(lastColor)
        if (sampleX != null && sampleY != null) {
            markerParams?.let { mp ->
                mp.x = (sampleX - dp(10)).coerceIn(0, max(0, screenWidth - dp(20)))
                mp.y = (sampleY - dp(10)).coerceIn(0, max(0, screenHeight - dp(20)))
                runCatching { windowManager?.updateViewLayout(marker, mp) }
            }
        }
        getSharedPreferences("mytools_prefs", MODE_PRIVATE).edit().putString("last_picker_color", hex).apply()
        sendBroadcast(Intent(ACTION_COLOR_PICKED).setPackage(packageName).apply {
            putExtra(EXTRA_COLOR, lastColor)
            putExtra(EXTRA_HEX, hex)
            putExtra(EXTRA_X, sampleX ?: -1)
            putExtra(EXTRA_Y, sampleY ?: -1)
        })
    }

    private fun markerDrawable(color: Int, stroke: Int = 2): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(stroke), Color.WHITE)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.app_icon).setContentTitle("MyTools Color Picker").setContentText("Pipet layar aktif").setOngoing(true).build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Color Picker", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun stopCaptureOnly() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
    }

    private fun removeOverlay() {
        sampleHandler.removeCallbacksAndMessages(null)
        runCatching { windowManager?.removeView(marker) }
        marker = null

        val wm = windowManager ?: return
        runCatching { bubble?.let { wm.removeView(it) } }
        runCatching { panel?.let { wm.removeView(it) } }
        bubble = null; panel = null
    }

    override fun onDestroy() {
        removeOverlay()
        stopCaptureOnly()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * density).toInt().coerceAtLeast(1)
    private fun solid(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
}
