package com.yuma.observatorylauncher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.graphics.toArgb
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors

/**
 * ホームのサイト項目を、ブラウザのアドレスバーを挟まず一つの面として開く。
 * URLと、Androidのファイル選択で得たcontent URIの双方を扱う。
 */
class WebShortcutActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        if (location.isBlank()) {
            finish()
            return
        }
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        window.decorView.setBackgroundColor(ObservatoryColors.Ground.toArgb())

        val localContent = Uri.parse(location).scheme == "content"
        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // ユーザー制作HTMLの主要機能を保つため必要。Android側のJavaScript interfaceは
            // 一切公開せず、外部URLから端末内content URIを読めないよう経路を分ける。
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = localContent
            settings.allowFileAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val uri = request.url
                    if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "content") {
                        return false
                    }
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }.getOrDefault(true)
                }
            }
        }
        setContentView(webView)
        if (savedInstanceState == null) webView.loadUrl(location) else webView.restoreState(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_LOCATION = "location"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, site: LayoutConfig.Site): Intent =
            Intent(context, WebShortcutActivity::class.java)
                .putExtra(EXTRA_LOCATION, site.location)
                .putExtra(EXTRA_TITLE, site.title)
    }
}

object WebShortcut {
    /** 省略されたschemeはHTTPSとして補い、WebViewへ渡せる場所だけを受け入れる。 */
    fun normalizeLocation(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val direct = Uri.parse(value)
        if (direct.scheme == "content" && !direct.authority.isNullOrBlank()) return direct.toString()

        val candidate = if (direct.scheme.isNullOrBlank()) "https://$value" else value
        val uri = Uri.parse(candidate)
        return if (
            (uri.scheme == "http" || uri.scheme == "https") &&
            !uri.host.isNullOrBlank()
        ) {
            uri.toString()
        } else {
            null
        }
    }

    fun title(context: Context, requested: String, location: String): String {
        requested.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val uri = Uri.parse(location)
        if (uri.scheme == "content") {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull()
            if (!name.isNullOrBlank()) return name.substringBeforeLast('.').ifBlank { name }
        }
        return uri.host?.removePrefix("www.")?.ifBlank { null } ?: "HTML"
    }

    fun open(context: Context, site: LayoutConfig.Site): Boolean = runCatching {
        context.startActivity(WebShortcutActivity.intent(context, site))
        true
    }.getOrDefault(false)
}
