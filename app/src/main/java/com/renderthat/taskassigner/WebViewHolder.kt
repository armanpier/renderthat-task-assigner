package com.renderthat.taskassigner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.view.ContextThemeWrapper

@SuppressLint("SetJavaScriptEnabled")
object WebViewHolder {

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    const val SIMULATION_URL = "file:///android_asset/simulation.html"
    const val LIVE_URL = "https://login.creator.renderthat.com/s/lidl-food-pipeline-tasks"

    private var webView: WebView? = null
    var isSimulationMode: Boolean = true
        private set

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    var onPageFinishedListener: (() -> Unit)? = null

    fun getOrCreateWebView(context: Context): WebView {
        if (webView == null) {
            isSimulationMode = AppPreferences.isSimulationMode(context)

            val themedContext = ContextThemeWrapper(context.applicationContext, R.style.Theme_RenderThatTaskAssigner)
            webView = WebView(themedContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    saveFormData = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    allowFileAccess = true
                    allowContentAccess = true
                    userAgentString = DESKTOP_USER_AGENT
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        consoleMessage?.let {
                            if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                                it.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                                LiveLogger.log("WebConsole", it.message())
                            }
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        LiveLogger.log("WebView", "Page started: $url")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        LiveLogger.log("WebView", "Page loaded: $url")
                        onPageFinishedListener?.invoke()
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            LiveLogger.log("WebView", "Page load error: ${error?.description}")
                        }
                    }
                }

                // Initial off-screen layout so dimensions match desktop rendering
                measure(
                    View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 1440, 2560)
            }

            loadCurrentMode()
        }
        return webView!!
    }

    fun setSimulationMode(simulation: Boolean) {
        if (isSimulationMode != simulation) {
            isSimulationMode = simulation
            LiveLogger.log("WebView", "Switching mode to: ${if (simulation) "SIMULATION" else "LIVE PORTAL"}")
            loadCurrentMode()
        }
    }

    fun loadCurrentMode() {
        val targetUrl = if (isSimulationMode) SIMULATION_URL else LIVE_URL
        LiveLogger.log("WebView", "Loading URL: $targetUrl")
        webView?.loadUrl(targetUrl)
    }

    fun reloadCurrentMode() {
        LiveLogger.log("WebView", "Reloading page...")
        webView?.reload()
    }

    fun attachToContainer(container: ViewGroup) {
        val wv = webView ?: return
        if (wv.parent === container) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.addView(wv)
        wv.requestLayout()
    }

    fun detachFromContainer() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, 1440, 2560)
        // DO NOT call wv.onPause() - we need the WebView to stay running in background!
    }

    fun evaluateJavascript(script: String, callback: ((String) -> Unit)? = null) {
        val wv = webView
        if (wv == null) {
            LiveLogger.log("WebView", "⚠️ evaluateJavascript called but WebView is null")
            callback?.invoke("null")
            return
        }
        val action = Runnable {
            try {
                wv.evaluateJavascript(script) { result ->
                    callback?.invoke(result ?: "null")
                }
            } catch (e: Throwable) {
                LiveLogger.log("WebView", "⚠️ evaluateJavascript error: ${e.message}")
                callback?.invoke("null")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }
}
