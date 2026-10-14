package com.imayx.host.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.imayx.host.ui.components.IxChip
import com.imayx.host.ui.theme.Ix

/** In-app preview. Navigation is restricted to the page host (and its own subresources). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(localUrl: String, publicUrl: String?, startPublic: Boolean, onClose: () -> Unit) {
    val c = Ix.c
    var usePublic by remember { mutableStateOf(startPublic && publicUrl != null) }
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val url = if (usePublic && publicUrl != null) publicUrl else localUrl
    val allowedHost = remember(url) { android.net.Uri.parse(url).host }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
            webView = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = c.text) }
            IxChip("Local", !usePublic, { usePublic = false })
            if (publicUrl != null) {
                androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
                IxChip("Public", usePublic, { usePublic = true })
            }
            Text(url, color = c.soft, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            IconButton(onClick = { webView?.reload() }) { Icon(Icons.Rounded.Refresh, "Reload", tint = c.text) }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = c.accent)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setBackgroundColor(android.graphics.Color.WHITE)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val h = request.url.host
                            return !(request.url.scheme in listOf("http", "https") && h == allowedHost)
                        }
                        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { progress = 1 }
                        override fun onPageFinished(view: WebView, u: String?) { progress = 100 }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                    }
                    webView = this
                }
            },
            update = { w -> if (w.tag != url) { w.tag = url; w.loadUrl(url) } }
        )
    }
}
