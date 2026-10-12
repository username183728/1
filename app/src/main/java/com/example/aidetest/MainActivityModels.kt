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

internal data class ConvCategory(
    val id: String,
    val icon: String,
    val title: String,
    val hint: String,
    val desc: String,
    val formDesc: String,
    val formats: List<String>,
    val mime: String
)

internal data class PageSnapshot(
    val name: String,
    val children: MutableList<View>,
    val scrollY: Int,
    val searchText: String,
    val searchVisible: Int,
    val lightweight: Boolean = false
)

internal data class ToolVisualTheme(
    val accent: Int,
    val surface: Int,
    val border: Int,
    val chip: String,
    val button: Int,
    val onButton: Int = Color.WHITE
)

internal data class HomeCategory(
    val name: String,
    val count: String,
    val icon: String,
    val ids: List<String>
)

internal data class BitChatMessage(
    val text: String,
    val fromUser: Boolean,
    val actions: List<BitActionBridge.Action> = emptyList()
)

internal data class BitAnswer(
    val text: String,
    val openToolId: String? = null,
    val actions: List<BitActionBridge.Action> = emptyList(),
    val autoOpen: Boolean = false
)

internal data class ToolHelp(
    val purpose: String,
    val whenUse: String,
    val example: String,
    val action: String = "Buka tool"
)



internal data class BatteryHero(val percent:TextView,val status:TextView,val icon:MdiIconView)

internal data class WifiHero(val ssid:TextView,val status:TextView,val rssi:TextView,val icon:MdiIconView,val linkBox:LinearLayout,val freqBox:LinearLayout,val channelBox:LinearLayout)

internal data class GhProgress(val step: Int, val detail: String, val current: Int = 0, val total: Int = 0)

internal data class GhResult(
    val owner: String, val repo: String, val branch: String,
    val files: Int, val bytes: Long, val commitSha: String,
    val url: String, val createdRepo: Boolean, val createdPrivate: Boolean,
    val workflowFiles: List<String> = emptyList(),
    val addedFiles: Int = 0,
    val updatedFiles: Int = 0,
    val deletedFiles: Int = 0,
    val unchangedFiles: Int = 0
)

internal data class GhActionJob(
    val id: Long, val name: String, val status: String, val conclusion: String?,
    val steps: List<Pair<String, String?>> = emptyList(), val htmlUrl: String = ""
)

internal data class GithubZipAnalysis(
    val files: List<String>,
    val dirs: List<String>,
    val suggestedRoot: String,
    val excludedDefaults: Set<String>
)

internal data class PhotoColor(val color: Int, val percent: Int)

internal data class CalculatorMode(val id: String, val name: String, val group: String)

