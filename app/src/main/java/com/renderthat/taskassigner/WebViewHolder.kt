package com.renderthat.taskassigner

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = DESKTOP_USER_AGENT
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        onPageFinishedListener?.invoke()
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
            loadCurrentMode()
        }
    }

    fun loadCurrentMode() {
        val targetUrl = if (isSimulationMode) SIMULATION_URL else LIVE_URL
        webView?.loadUrl(targetUrl)
    }

    fun attachToContainer(container: ViewGroup) {
        val wv = webView ?: return
        if (wv.parent === container) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv)
    }

    fun detachFromContainer() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        // Maintain layout dimensions while detached so shadow DOM and desktop viewport remain active
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, 1440, 2560)
        // DO NOT call wv.onPause() - we need the WebView to stay running in background!
    }

    fun evaluateJavascript(script: String, callback: ((String) -> Unit)? = null) {
        webView?.post {
            webView?.evaluateJavascript(script) { result ->
                callback?.invoke(result)
            }
        }
    }
}
