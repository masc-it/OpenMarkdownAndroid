package com.mascit.openmarkdown

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.mascit.openmarkdown.ui.theme.OpenMarkdownTheme
import org.json.JSONObject
import com.mascit.openmarkdown.util.RecentFilesStore
import com.mascit.openmarkdown.util.TableOfContents
import com.mascit.openmarkdown.util.TocEntry
import java.io.File

private const val TAG = "OpenMarkdown"

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    when {
        android.os.Build.VERSION.SDK_INT >= 33 -> getParcelableExtra(key, T::class.java)
        else -> getParcelableExtra(key) as? T
    }

class ViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ORIGINAL_URI = "com.mascit.openmarkdown.ORIGINAL_URI"
        const val EXTRA_CACHE_PATH = "com.mascit.openmarkdown.CACHE_PATH"
    }

    private val recentFiles by lazy { RecentFilesStore(this) }

    private var pendingMarkdown: String? = null
    private var originalMarkdown: String? = null
    private var webView: WebView? = null
    private var pageLoaded = false

    private val markdownBridge = MarkdownBridge()

    private inner class MarkdownBridge {
        @Volatile
        private var content: String? = null

        fun push(text: String) {
            content = text
        }

        @android.webkit.JavascriptInterface
        fun getContent(): String {
            val result = content
            content = null
            return result ?: ""
        }
    }

    private var tocEntries by mutableStateOf<List<TocEntry>>(emptyList())
    private var showToc by mutableStateOf(false)
    private var activeHeadingIndex by mutableStateOf<Int?>(null)

    private inner class TocBridge {
        @android.webkit.JavascriptInterface
        fun onHeadingChanged(index: Int) {
            activeHeadingIndex = index
        }
    }

    // ── Theme bridge ───────────────────────────────────────────────────────

    private var themeBridge: ThemeBridge? = null

    private inner class ThemeBridge(private val colors: ColorScheme) {
        @android.webkit.JavascriptInterface
        fun getThemeColors(): String {
            val argb = { c: androidx.compose.ui.graphics.Color ->
                String.format("#%06x", c.toArgb().toLong() and 0x00FFFFFFL)
            }
            return JSONObject().apply {
                put("--bg", argb(colors.background))
                put("--surface", argb(colors.surface))
                put("--text", argb(colors.onBackground))
                put("--text-secondary", argb(colors.onSurfaceVariant))
                put("--primary", argb(colors.primary))
                put("--code-bg", argb(colors.surfaceVariant))
                put("--code-inline", argb(colors.surfaceVariant.copy(alpha = 0.4f)))
                put("--border", argb(colors.outline))
                put("--link", argb(colors.primary))
            }.toString()
        }
    }

    private fun applyTheme() {
        val bridge = themeBridge ?: return
        webView?.evaluateJavascript(
            "applyThemeColors(${bridge.getThemeColors()})", null
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate intent=${intent} action=${intent?.action}")
        pendingMarkdown = readMarkdownFromIntent()
        originalMarkdown = pendingMarkdown
        tocEntries = pendingMarkdown?.let { TableOfContents.parse(it).entries } ?: emptyList()
        Log.d(TAG, "onCreate pendingMarkdown length=${pendingMarkdown?.length} toc=${tocEntries.size}")

        saveToRecent()

        setContent {
            OpenMarkdownTheme {
                val colorScheme = MaterialTheme.colorScheme
                ViewerScreen(
                    tocEntries = tocEntries,
                    showToc = showToc,
                    activeHeadingIndex = activeHeadingIndex,
                    markdownContent = originalMarkdown,
                    onTocRequest = { showToc = true },
                    onTocDismiss = { showToc = false },
                    onTocEntryClick = { index -> scrollToHeading(index) },
                    onShare = { shareMarkdown() },
                    webViewFactory = { createWebView(colorScheme) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent intent=$intent action=${intent?.action}")
        setIntent(intent)
        pendingMarkdown = readMarkdownFromIntent()
        originalMarkdown = pendingMarkdown
        tocEntries = pendingMarkdown?.let { TableOfContents.parse(it).entries } ?: emptyList()
        Log.d(TAG, "onNewIntent pendingMarkdown length=${pendingMarkdown?.length}")
        saveToRecent()
        if (pageLoaded) {
            renderPendingMarkdown()
        }
    }

    // ── WebView ──────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(colorScheme: ColorScheme): WebView {
        return WebView(this).apply {
            setBackgroundColor(colorScheme.background.toArgb())
            settings.javaScriptEnabled = true
            addJavascriptInterface(markdownBridge, "AndroidBridge")
            addJavascriptInterface(TocBridge(), "Android")

            val bridge = ThemeBridge(colorScheme)
            themeBridge = bridge
            addJavascriptInterface(bridge, "Theme")

            webChromeClient = object : WebChromeClient() {}
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "WebView onPageFinished url=$url")
                    pageLoaded = true
                    applyTheme()
                    renderPendingMarkdown()
                }
            }
            loadUrl("file:///android_asset/viewer.html")
            webView = this
        }
    }

    private fun scrollToHeading(index: Int) {
        webView?.evaluateJavascript("scrollToHeading($index)", null)
        showToc = false
    }

    // ── Intent / file reading ────────────────────────────────────────────

    private fun saveToRecent() {
        val text = pendingMarkdown ?: return

        val uriStr = intent?.getStringExtra(EXTRA_ORIGINAL_URI)
            ?: intent?.data?.toString()
            ?: intent?.let { intent.parcelable<android.net.Uri>(Intent.EXTRA_STREAM) }?.toString()
            ?: return

        val title = TableOfContents.parse(text).extractTitle(text) ?: uriStr.substringAfterLast('/')
        recentFiles.push(uriStr, title, text)
        Log.d(TAG, "saveToRecent uri=$uriStr title=$title")
    }

    private fun readMarkdownFromIntent(): String? {
        val action = intent?.action
        Log.d(TAG, "readMarkdownFromIntent action=$action")

        val cachePath = intent?.getStringExtra(EXTRA_CACHE_PATH)
        if (cachePath != null) {
            Log.d(TAG, "readMarkdownFromIntent reading from cache: $cachePath")
            return try {
                File(cachePath).readText()
            } catch (e: Exception) {
                Log.e(TAG, "readMarkdownFromIntent cache read failed", e)
                "Error reading cached file: ${e.message}"
            }
        }

        val dataUri: Uri? = intent?.data
        if (dataUri != null) {
            Log.d(TAG, "readMarkdownFromIntent reading from data URI: $dataUri")
            return readFromUri(dataUri)
        }

        if (action == Intent.ACTION_SEND) {
            val streamUri: Uri? = intent.parcelable(Intent.EXTRA_STREAM)
            if (streamUri != null) {
                Log.d(TAG, "readMarkdownFromIntent reading from EXTRA_STREAM: $streamUri")
                return readFromUri(streamUri)
            }

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
            "Error reading file: ${e.message}"
        }
    }

    private fun shareMarkdown() {
        val text = originalMarkdown ?: return
        val title = TableOfContents.parse(text).extractTitle(text) ?: "document"
        val safeName = title.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".md"

        val cacheFile = File(cacheDir, "share").apply { mkdirs() }
            .let { File(it, safeName) }
        cacheFile.writeText(text)

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            cacheFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share markdown"))
    }

    private fun renderPendingMarkdown() {
        val text = pendingMarkdown
        val view = webView
        Log.d(TAG, "renderPendingMarkdown textNull=${text == null} viewNull=${view == null}")
        if (text == null || view == null) return
        pendingMarkdown = null

        markdownBridge.push(text)
        view.evaluateJavascript("renderFromAndroid()", null)
    }
}

// ── Composable screen ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(
    tocEntries: List<TocEntry>,
    showToc: Boolean,
    activeHeadingIndex: Int?,
    markdownContent: String?,
    onTocRequest: () -> Unit,
    onTocDismiss: () -> Unit,
    onTocEntryClick: (Int) -> Unit,
    onShare: () -> Unit,
    webViewFactory: () -> WebView
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (tocEntries.isNotEmpty() || markdownContent != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (tocEntries.isNotEmpty()) {
                        Text(
                            modifier = Modifier
                                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                                .clickable(onClick = onTocRequest),
                            text = "Contents",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        )
                    }
                    if (markdownContent != null) {
                        Text(
                            modifier = Modifier
                                .padding(top = 4.dp, bottom = 4.dp)
                                .clickable(onClick = onShare),
                            text = "Share",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        )
                    }
                }
            }

            AndroidView(
                factory = { webViewFactory() },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
        }
    }

    if (showToc) {
        ModalBottomSheet(
            onDismissRequest = onTocDismiss,
            sheetState = sheetState
        ) {
            TocSheetContent(
                entries = tocEntries,
                activeIndex = activeHeadingIndex,
                onEntryClick = onTocEntryClick
            )
        }
    }
}

@Composable
private fun TocSheetContent(
    entries: List<TocEntry>,
    activeIndex: Int?,
    onEntryClick: (Int) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (activeIndex != null && activeIndex < entries.size) {
            listState.scrollToItem(activeIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = "Contents",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(entries) { index, entry ->
                val isActive = index == activeIndex
                val indent = (entry.level - 1) * 16
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEntryClick(index) }
                        .then(
                            if (isActive) Modifier.background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            ) else Modifier
                        )
                        .padding(start = indent.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.text,
                        fontSize = 15.sp,
                        fontWeight = if (isActive) FontWeight.Bold
                                    else if (entry.level <= 2) FontWeight.Medium
                                    else FontWeight.Normal,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
