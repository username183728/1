package com.example.aidetest

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.concurrent.RejectedExecutionException
import android.Manifest
import android.app.*
import android.app.usage.StorageStatsManager
import android.os.StatFs
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import android.provider.Settings
import android.provider.MediaStore
import android.media.ImageReader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.text.InputType
import android.view.*
import android.widget.*
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.MultiFormatReader
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.airbnb.lottie.LottieListener

internal class MiniLineChart(ctx:Context):View(ctx){var values:List<Float> = emptyList();var lineColor=Color.DKGRAY;var fillColor=Color.TRANSPARENT;var minValue=0f;var maxValue=100f;private val p=Paint(Paint.ANTI_ALIAS_FLAG);override fun onDraw(c:Canvas){super.onDraw(c);val w=width.toFloat();val h=height.toFloat();val den=resources.displayMetrics.density;p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=Color.rgb(232,236,239);for(i in 1..4){val y=h*i/5f;c.drawLine(0f,y,w,y,p)};if(values.isEmpty())return;val path=android.graphics.Path();values.forEachIndexed{i,v->val x=if(values.size==1)w/2 else i.toFloat()/(values.size-1)*w;val y=h-((v-minValue)/(maxValue-minValue).coerceAtLeast(1f)).coerceIn(0f,1f)*h;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};p.color=fillColor;p.style=Paint.Style.FILL;val fill=android.graphics.Path(path);fill.lineTo(w,h);fill.lineTo(0f,h);fill.close();c.drawPath(fill,p);p.color=lineColor;p.style=Paint.Style.STROKE;p.strokeWidth=2f*den;c.drawPath(path,p);val last=values.last();val x=if(values.size==1)w/2 else w;val y=h-((last-minValue)/(maxValue-minValue).coerceAtLeast(1f)).coerceIn(0f,1f)*h;p.style=Paint.Style.FILL;c.drawCircle(x,y,4f*den,p)}}

internal class GhCancelException : RuntimeException("Upload dibatalkan")

internal class GhField(val root: LinearLayout, val edit: EditText, val error: TextView, val row: LinearLayout)

internal class ExprParser(private val source: String, private val scientific: Boolean) {
    internal var pos = 0
    internal val s = source.replace("×", "*").replace("÷", "/").replace("−", "-").replace(" ", "")
    fun parse(): Double { val v = expression(); if (pos != s.length) error("Karakter tidak dikenal") ; return v }
    internal fun expression(): Double { var v = term(); while (pos < s.length) { when(s[pos]) { '+' -> {pos++; v += term()} ; '-' -> {pos++; v -= term()} ; else -> return v } }; return v }
    internal fun term(): Double { var v = power(); while (pos < s.length) { when(s[pos]) { '*' -> {pos++; v *= power()} ; '/' -> {pos++; val d=power(); if (d==0.0) error("Tidak bisa dibagi 0"); v /= d} ; '%' -> {pos++; v %= power()} ; else -> return v } }; return v }
    internal fun power(): Double { var v = unary(); if (pos < s.length && s[pos]=='^') {pos++; v = Math.pow(v, power())}; return v }
    internal fun unary(): Double {
        if (pos < s.length && s[pos]=='+') {pos++; return unary()}
        if (pos < s.length && s[pos]=='-') {pos++; return -unary()}
        if (pos < s.length && s[pos]=='(') {pos++; val v=expression(); if(pos>=s.length||s[pos]!=')') error("Kurung belum lengkap"); pos++; return v}
        if (pos < s.length && s[pos].isLetter()) {
            val start=pos; while(pos<s.length && s[pos].isLetter()) pos++
            val name=s.substring(start,pos).lowercase(Locale.getDefault())
            if(name=="pi") return Math.PI
            if(pos>=s.length || s[pos]!='(') error("Gunakan kurung setelah $name")
            pos++; val x=expression(); if(pos>=s.length||s[pos]!=')') error("Kurung belum lengkap"); pos++
            return when(name) {
                "sqrt" -> Math.sqrt(x)
                "sin" -> Math.sin(Math.toRadians(x))
                "cos" -> Math.cos(Math.toRadians(x))
                "tan" -> Math.tan(Math.toRadians(x))
                "log" -> Math.log10(x)
                "ln" -> Math.log(x)
                else -> error("Fungsi $name tidak didukung")
            }
        }
        val start=pos; while(pos<s.length && (s[pos].isDigit()||s[pos]=='.')) pos++
        if(start==pos) error("Angka diharapkan")
        return s.substring(start,pos).toDouble()
    }
}

internal class DebouncedSearchWatcher(
    internal val fn: (String) -> Unit
) : android.text.TextWatcher {
    internal val handler = Handler(Looper.getMainLooper())
    internal var pending: Runnable? = null

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        val value = s?.toString().orEmpty()
        pending?.let(handler::removeCallbacks)
        val task = Runnable { fn(value) }
        pending = task
        handler.postDelayed(task, 110L)
    }

    override fun afterTextChanged(s: android.text.Editable?) = Unit
}


internal object JSONObjectLite {
    fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
