package app.dkord.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.dkord.data.auth.AuthStore
import app.dkord.data.yt.InnerTubeClient
import org.koin.compose.koinInject

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&hl=en&continue=https%3A%2F%2Fmusic.youtube.com%2F"

/**
 * Google sign-in in a WebView. A desktop Chrome user agent is required: Google rejects WebView UAs.
 * Cookies are read from [CookieManager] once we land on music.youtube.com with a SAPISID cookie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: (success: Boolean) -> Unit, title: String = "Sign in") {
    val auth: AuthStore = koinInject()
    val ctx = LocalContext.current
    val web = remember {
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = InnerTubeClient.WEB_UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = Unit
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith("https://music.youtube.com") && auth.captureFromWebView()) onDone(true)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
            loadUrl(LOGIN_URL)
        }
    }
    BackHandler { if (web.canGoBack()) web.goBack() else onDone(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { pad ->
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().padding(pad))
    }
}
