package com.blazikener.devin.ui.bots

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blazikener.devin.graph
import com.blazikener.devin.ui.components.openUrl

/**
 * The public API has no Bots endpoints, so Bots are shown from Devin on the web,
 * signed in with the user's own web login. Session links open natively.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val org = settings.webOrg
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Bots") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            actions = {
                if (org != null) {
                    IconButton(onClick = { web?.reload() }) { Icon(Icons.Outlined.Refresh, "Reload") }
                    IconButton(onClick = { openUrl(ctx, web?.url ?: botsUrl(org)) }) { Icon(Icons.Outlined.OpenInBrowser, "Open in browser") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Change organization") }, onClick = {
                                menu = false
                                g.settings.update { it.copy(webOrg = null) }
                            })
                        }
                    }
                }
            },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (org == null) OrgForm { g.settings.update { s -> s.copy(webOrg = it) } }
            else BotsWeb(org, onOpenSession, onWeb = { web = it }, onCanGoBack = { canGoBack = it })
        }
    }
    BackHandler(enabled = org != null && canGoBack) { web?.goBack() }
}

@Composable
private fun OrgForm(onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val org = parseWebOrg(text)
    Column(
        Modifier.fillMaxSize().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Open your Bots", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Bots are shown from Devin on the web. Enter your organization's name from its web address, app.devin.ai/org/<name>, or paste the address.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Organization") }, placeholder = { Text("acme") },
            isError = text.isNotBlank() && org == null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { org?.let(onSave) }),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { org?.let(onSave) }, enabled = org != null) { Text("Open Bots") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BotsWeb(org: String, onOpenSession: (String) -> Unit, onWeb: (WebView?) -> Unit, onCanGoBack: (Boolean) -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    // Restores the page after a native session screen disposes this WebView.
    var lastUrl by rememberSaveable(org) { mutableStateOf(botsUrl(org)) }
    val ctx = LocalContext.current
    val view = remember(org) {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    sessionIdFromWebUrl(url)?.let { onOpenSession(it); return true }
                    if (request.url.scheme !in setOf("http", "https")) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        return true
                    }
                    return false
                }

                // The web app routes client-side, so session pages opened in-page arrive here.
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    val id = sessionIdFromWebUrl(url)
                    if (id != null) {
                        if (view.canGoBack()) view.goBack() else view.loadUrl(botsUrl(org))
                        onOpenSession(id)
                    } else if (url.startsWith("https://app.devin.ai/")) lastUrl = url
                    onCanGoBack(view.canGoBack())
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { error = null }

                override fun onPageFinished(view: WebView, url: String?) {
                    CookieManager.getInstance().flush()
                    onCanGoBack(view.canGoBack())
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                    if (request.isForMainFrame) error = "Couldn't load Devin (${err.description})."
                }
            }
            loadUrl(lastUrl)
        }
    }
    DisposableEffect(view) {
        onWeb(view)
        onDispose { onWeb(null); onCanGoBack(false); view.destroy() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ view }, Modifier.fillMaxSize())
        if (progress < 1f) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().align(Alignment.TopCenter))
        error?.let { e ->
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(e, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { error = null; view.reload() }) { Text("Retry") }
            }
        }
    }
}
