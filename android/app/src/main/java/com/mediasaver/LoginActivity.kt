package com.mediasaver

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Shows a site's own login page so the user can sign in themselves. The app never
 * sees the password - only the session cookies the site sets afterwards, which
 * are handed to yt-dlp so it can read posts that require an account.
 */
class LoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SITE = "site"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val siteKey = intent.getStringExtra(EXTRA_SITE)
        val site = CookieStore.SITES.firstOrNull { it.key == siteKey }
        if (site == null) {
            finish()
            return
        }

        CookieManager.getInstance().setAcceptCookie(true)

        setContent {
            MediaSaverTheme {
                var webView by remember { mutableStateOf<WebView?>(null) }
                var loading by remember { mutableStateOf(true) }
                var done by remember { mutableStateOf(false) }

                fun capture(announceFailure: Boolean) {
                    if (done) return
                    if (CookieStore.capture(this@LoginActivity, site)) {
                        done = true
                        Toast.makeText(this@LoginActivity, "Signed in to ${site.label}", Toast.LENGTH_SHORT).show()
                        finish()
                    } else if (announceFailure) {
                        Toast.makeText(
                            this@LoginActivity,
                            "No sign-in found yet. Finish signing in, then tap Done.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                BackHandler(enabled = true) {
                    val view = webView
                    if (view != null && view.canGoBack()) view.goBack() else finish()
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Sign in to ${site.label}") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close")
                                }
                            },
                            actions = {
                                TextButton(onClick = { capture(announceFailure = true) }) { Text("Done") }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (loading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                WebView(context).apply {
                                    webView = this
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    // The stock WebView user agent carries a "wv"
                                    // marker. X detects it and serves a blank page
                                    // instead of its login form, so present as
                                    // ordinary mobile Chrome.
                                    settings.userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                                            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                    CookieManager.getInstance()
                                        .setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            loading = false
                                            // The session cookie only appears once the
                                            // login actually succeeds, so it doubles as
                                            // the signal that we are finished here.
                                            if (CookieStore.isSignedIn(site)) capture(announceFailure = false)
                                        }
                                    }
                                    loadUrl(site.loginUrl)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
