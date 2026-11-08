package de.luca.mitfahrt.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.luca.mitfahrt.data.NewLiveMap
import kotlinx.coroutines.delay

/** NEW owns the map, its markers and line selection. No JavaScript bridge into app data. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NewLiveMapScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var slow by remember { mutableStateOf(false) }
    fun openBrowser(url: String = NewLiveMap.URL) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { error = "Kein Browser verfügbar." }
    }
    LaunchedEffect(loading, retry) {
        slow = false
        if (loading) { delay(20_000); slow = true }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                    Column(Modifier.weight(1f)) {
                        Text("NEW-Livekarte", style = MaterialTheme.typography.titleMedium)
                        Text("livebus.new.de", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { error = null; loading = true; retry++ }) { Icon(Icons.Outlined.Refresh, "Karte neu laden") }
                    IconButton(onClick = { openBrowser() }) { Icon(Icons.Outlined.OpenInBrowser, "Im Browser öffnen") }
                }
                if (loading && error == null) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (lifecycle.isAtLeast(Lifecycle.State.STARTED) && error == null) key(retry) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                WebView(viewContext).apply {
                                    webView = this
                                    loading = true
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    settings.setGeolocationEnabled(false)
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                            if (!request.isForMainFrame) return false
                                            val url = request.url.toString()
                                            if (NewLiveMap.isInternal(url)) return false
                                            if (request.hasGesture() && NewLiveMap.isExternalLink(url)) openBrowser(url)
                                            return true
                                        }
                                        override fun onPageFinished(view: WebView, url: String) { loading = false }
                                        override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                            if (request.isForMainFrame) error = "Die Karte ist gerade nicht erreichbar."
                                        }
                                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                            if (request.isForMainFrame) error = "Die Karte ist gerade nicht erreichbar."
                                        }
                                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                                            handler.cancel()
                                            error = "Die sichere Verbindung zur Karte ist fehlgeschlagen."
                                        }
                                    }
                                    loadUrl(NewLiveMap.URL)
                                }
                            },
                            onRelease = { view ->
                                view.stopLoading()
                                view.webViewClient = WebViewClient()
                                view.destroy()
                                if (webView === view) webView = null
                            }
                        )
                    }
                    if (error != null) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { error = null; loading = true; retry++ }) { Text("Erneut versuchen") }
                        TextButton(onClick = { openBrowser() }) { Text("Im Browser öffnen") }
                    }
                }
                if (slow && loading && error == null) TextButton(onClick = { openBrowser() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Lädt länger · Im Browser öffnen")
                }
            }
        }
    }
}
