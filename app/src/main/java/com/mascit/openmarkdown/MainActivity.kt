package com.mascit.openmarkdown

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mascit.openmarkdown.ui.theme.OpenMarkdownTheme
import java.io.File

private const val TAG = "OpenMarkdown"

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    when {
        android.os.Build.VERSION.SDK_INT >= 33 -> getParcelableExtra(key, T::class.java)
        else -> getParcelableExtra(key) as? T
    }

class MainActivity : ComponentActivity() {

    // We queue markdown here if WebView hasn't finished loading yet.
    // Teammates: WebView is async; we can't render until the page loads.
    private var pendingMarkdown: String? = null
    private var webView: WebView? = null
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate intent=${intent} action=${intent?.action} data=${intent?.data}")
        dumpIntentExtras(intent)

        pendingMarkdown = readMarkdownFromIntent()
        Log.d(TAG, "onCreate pendingMarkdown length=${pendingMarkdown?.length}, null=${pendingMarkdown == null}")

        setContent {
            OpenMarkdownTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(WindowInsets.systemBars.asPaddingValues())
                ) {
                    AndroidView(
                        factory = { context ->
                            Log.d(TAG, "WebView factory creating")
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                        Log.d(TAG, "JS console: ${message.message()} -- ${message.sourceId()}:${message.lineNumber()}")
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        Log.d(TAG, "WebView onPageFinished url=$url")
                                        pageLoaded = true
                                        renderPendingMarkdown()
                                    }
                                }
                                loadUrl("file:///android_asset/viewer.html")
                                webView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent intent=$intent action=${intent?.action} data=${intent?.data}")
        dumpIntentExtras(intent)
        setIntent(intent)
        pendingMarkdown = readMarkdownFromIntent()
        Log.d(TAG, "onNewIntent pendingMarkdown length=${pendingMarkdown?.length}, null=${pendingMarkdown == null}")
        if (pageLoaded) {
            renderPendingMarkdown()
        }
    }

    private fun dumpIntentExtras(intent: Intent?) {
        if (intent == null) return
        val extras = intent.extras
        if (extras == null) {
            Log.d(TAG, "Intent extras: null")
            return
        }
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            val value = extras.get(key)
            Log.d(TAG, "Intent extra: key=$key value=$value type=${value?.javaClass?.simpleName}")
        }
    }

    /**
     * Extracts text from the incoming intent.
     * Handles:
     *   - ACTION_VIEW / ACTION_EDIT with intent.data URI
     *   - ACTION_SEND with EXTRA_STREAM (file URI from Telegram, etc.)
     *   - ACTION_SEND with EXTRA_TEXT (shared plain text)
     */
    private fun readMarkdownFromIntent(): String? {
        val action = intent?.action
        Log.d(TAG, "readMarkdownFromIntent action=$action")

        // Case 1: VIEW / EDIT with a URI
        val dataUri: Uri? = intent?.data
        if (dataUri != null) {
            Log.d(TAG, "readMarkdownFromIntent reading from data URI: $dataUri scheme=${dataUri.scheme}")
            return readFromUri(dataUri)
        }

        // Case 2: SEND with a file stream
        if (action == Intent.ACTION_SEND) {
            val streamUri: Uri? = intent.parcelable(Intent.EXTRA_STREAM)
            if (streamUri != null) {
                Log.d(TAG, "readMarkdownFromIntent reading from EXTRA_STREAM: $streamUri")
                return readFromUri(streamUri)
            }

            // Case 3: SEND with plain text
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                Log.d(TAG, "readMarkdownFromIntent reading from EXTRA_TEXT, length=${text.length}")
                return text
            }
        }

        Log.w(TAG, "readMarkdownFromIntent: no readable source found")
        return null
    }

    private fun readFromUri(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "content" -> {
                    Log.d(TAG, "readFromUri opening content resolver for $uri")
                    contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText().also {
                            Log.d(TAG, "readFromUri content read success, length=${it.length}")
                        }
                    }
                }
                "file" -> {
                    val path = uri.path
                    Log.d(TAG, "readFromUri reading file path=$path")
                    path?.let { File(it).readText() }
                }
                else -> {
                    Log.w(TAG, "readFromUri unsupported scheme: ${uri.scheme}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readFromUri failed for $uri", e)
            // Show the error in the viewer so the user isn't left with a blank screen
            "Error reading file: ${e.message}"
        }
    }

    /**
     * Sends queued markdown into the WebView.
     * Base64 avoids escaping hell between Kotlin and JS.
     */
    private fun renderPendingMarkdown() {
        val text = pendingMarkdown
        val view = webView
        Log.d(TAG, "renderPendingMarkdown textNull=${text == null} viewNull=${view == null}")
        if (text == null || view == null) return
        pendingMarkdown = null // consume once

        val base64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        Log.d(TAG, "renderPendingMarkdown calling JS with base64 length=${base64.length}")
        view.evaluateJavascript("renderMarkdownBase64('$base64')") { result ->
            Log.d(TAG, "renderPendingMarkdown JS callback result=$result")
        }
    }
}
