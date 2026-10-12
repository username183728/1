package com.example.aidetest

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText

/**
 * Live Preview for HTML/CSS/JS projects.
 * Debounced update (400ms), injects linked CSS+JS from project buffers, captures console.
 */
object EditorLivePreview {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private const val DEBOUNCE_MS = 400L
    private var attachedEdit: EditText? = null
    private var watcher: TextWatcher? = null
    private var sessionGeneration: Long = 0L
    private var pollingActivity: MainActivity? = null
    private var pollingWebView: WebView? = null
    private var activityRef: MainActivity? = null
    /** Latest console sink for the active live-preview session. Cleared on detach to avoid retaining UI callbacks. */
    private var consoleSink: ((String) -> Unit)? = null

    private fun reportConsole(message: String) {
        runCatching { consoleSink?.invoke(message) }
    }

    fun attach(
        activity: MainActivity,
        edit: EditText,
        webView: WebView,
        onConsole: (String) -> Unit
    ) {
        detach()
        val session = ++sessionGeneration
        consoleSink = onConsole
        activityRef = activity
        attachedEdit = edit

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                val crashed = if (android.os.Build.VERSION.SDK_INT >= 26) detail?.didCrash() == true else true
                reportConsole(if (crashed) "[ERR] Live Preview renderer crash" else "[WARN] Live Preview renderer dihentikan sistem")
                stopBufferPolling()
                val activeActivity = activityRef
                detach()
                runCatching {
                    view?.stopLoading()
                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                    view?.destroy()
                }
                if (activeActivity === activity && activity.currentPage == "Editor" && !activity.isFinishing && !activity.isDestroyed) {
                    activity.editorLivePreviewWeb = null
                }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                if (msg == null) return true
                val level = when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "ERR"
                    ConsoleMessage.MessageLevel.WARNING -> "WARN"
                    else -> "LOG"
                }
                onConsole("[$level] ${msg.message()}  (${msg.sourceId()}:${msg.lineNumber()})")
                return true
            }
        }

        val tw = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val run = Runnable {
                    if (session != sessionGeneration || attachedEdit !== edit || !webView.isAttachedToWindow) return@Runnable
                    try {
                        activity.flushEditorCurrentToBuffer(writeDisk = false)
                        val html = activity.buildLinkedWebPreviewHtml()
                        val base = activity.editorProjectDir
                            ?.takeIf { it.isDirectory }
                            ?.toURI()?.toString()
                        webView.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
                    } catch (e: Exception) {
                        reportConsole("[ERR] Live preview: ${e.message}")
                    }
                }
                pending = run
                handler.postDelayed(run, DEBOUNCE_MS)
            }
        }
        watcher = tw
        edit.addTextChangedListener(tw)
        // initial render
        handler.postDelayed({
            if (session != sessionGeneration || attachedEdit !== edit || !webView.isAttachedToWindow) return@postDelayed
            try {
                activity.flushEditorCurrentToBuffer(writeDisk = false)
                val html = activity.buildLinkedWebPreviewHtml()
                val base = activity.editorProjectDir?.takeIf { it.isDirectory }?.toURI()?.toString()
                webView.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
            } catch (e: Exception) { reportConsole("[ERR] ${e.message}") }
        }, 80L)
    }

    fun detach() {
        sessionGeneration++
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        val ed = attachedEdit
        val tw = watcher
        if (ed != null && tw != null) {
            try { ed.removeTextChangedListener(tw) } catch (e: Exception) { reportConsole("[ERR] ${e.message}") }
        }
        attachedEdit = null
        watcher = null
        activityRef = null
        consoleSink = null
    }

    /** Force one immediate refresh (e.g. after switching tab). */
    fun refreshNow(activity: MainActivity, webView: WebView) {
        try {
            activity.flushEditorCurrentToBuffer(writeDisk = false)
            val html = activity.buildLinkedWebPreviewHtml()
            val base = activity.editorProjectDir?.takeIf { it.isDirectory }?.toURI()?.toString()
            webView.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
        } catch (e: Exception) { reportConsole("[ERR] ${e.message}") }
    }


    private var pollRunnable: Runnable? = null

    /**
     * Poll project buffers every [intervalMs] and refresh WebView.
     * Useful when the EditText is not on-screen (full-screen live preview).
     */
    fun startBufferPolling(activity: MainActivity, webView: WebView, intervalMs: Long = 800L) {
        stopBufferPolling()
        val session = sessionGeneration
        val keyAtStart = activity.editorCurrentBufferKey
        val fileAtStart = activity.editorFile?.absolutePath
        pollingActivity = activity
        pollingWebView = webView
        val run = object : Runnable {
            override fun run() {
                val stillValid = session == sessionGeneration &&
                    pollingActivity === activity &&
                    pollingWebView === webView &&
                    !activity.isFinishing &&
                    !activity.isDestroyed &&
                    webView.isAttachedToWindow &&
                    activity.currentPage == "Editor" &&
                    activity.editorCurrentBufferKey == keyAtStart &&
                    activity.editorFile?.absolutePath == fileAtStart
                if (!stillValid) {
                    stopBufferPolling()
                    return
                }
                try {
                    refreshNow(activity, webView)
                } catch (e: Exception) {
                    reportConsole("[ERR] Live preview: ${e.message}")
                }
                handler.postDelayed(this, intervalMs)
            }
        }
        pollRunnable = run
        handler.post(run)
    }

    fun stopBufferPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        pollingActivity = null
        pollingWebView = null
    }

}
