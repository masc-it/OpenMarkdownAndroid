package com.mascit.openmarkdown

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mascit.openmarkdown.ui.theme.LocalThemePreference
import com.mascit.openmarkdown.ui.theme.OpenMarkdownTheme
import com.mascit.openmarkdown.util.RecentFile
import com.mascit.openmarkdown.util.RecentFilesStore
import com.mascit.openmarkdown.util.ThemeMode

// ── Palette (Tailwind Slate) ──────────────────────────────────────────────────

private data class WelcomeColors(
    val background: Color,
    val title: Color,
    val hash: Color,
    val tagline: Color,
    val sectionLabel: Color,
    val rowTitle: Color,
    val rowTime: Color,
    val rowBg: Color,
    val rowBgPressed: Color
)

private val LightColors = WelcomeColors(
    background    = Color(0xFFFFFFFF),
    title         = Color(0xFF0F172A),   // slate-900
    hash          = Color(0xFF94A3B8),   // slate-400
    tagline       = Color(0xFF64748B),   // slate-500
    sectionLabel  = Color(0xFFCBD5E1),   // slate-300
    rowTitle      = Color(0xFF0F172A),   // slate-900
    rowTime       = Color(0xFF94A3B8),   // slate-400
    rowBg         = Color(0xFFF8FAFC),   // slate-50
    rowBgPressed  = Color(0xFFF1F5F9)    // slate-100
)

private val DarkColors = WelcomeColors(
    background    = Color(0xFF0D1117),   // github dark
    title         = Color(0xFFF1F5F9),   // slate-100
    hash          = Color(0xFF334155),   // slate-700
    tagline       = Color(0xFF475569),   // slate-600
    sectionLabel  = Color(0xFF334155),   // slate-700
    rowTitle      = Color(0xFFE2E8F0),   // slate-200
    rowTime       = Color(0xFF475569),   // slate-600
    rowBg         = Color(0xFF161B22),   // github card
    rowBgPressed  = Color(0xFF1C2433)
)

// ── Activity ──────────────────────────────────────────────────────────────────

class WelcomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.LIGHT) }

            OpenMarkdownTheme {
                val pref = LocalThemePreference.current
                // Sync state from pref on first composition
                if (pref != null) themeMode = pref.getThemeMode()

                val effectiveDark = when (themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                val colors = if (effectiveDark) DarkColors else LightColors
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .padding(WindowInsets.systemBars.asPaddingValues()),
                    contentAlignment = Alignment.Center
                ) {
                    WelcomeContent(colors, themeMode = themeMode, onThemeToggle = {
                        val next = when (themeMode) {
                            ThemeMode.LIGHT -> ThemeMode.DARK
                            ThemeMode.DARK -> ThemeMode.LIGHT
                        }
                        pref?.setThemeMode(next)
                        themeMode = next
                    })
                }
            }
        }
    }
}

// ── Composables ───────────────────────────────────────────────────────────────

@Composable
private fun WelcomeContent(
    colors: WelcomeColors,
    themeMode: ThemeMode,
    onThemeToggle: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { RecentFilesStore(context) }
    var recentFiles by remember { mutableStateOf(store.list()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recentFiles = store.list()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Theme toggle — top-right
        ThemeSwitchButton(
            mode = themeMode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 16.dp),
            onClick = onThemeToggle
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Hero
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.hash)) { append("# ") }
                    withStyle(SpanStyle(color = colors.title)) { append("OpenMarkdown") }
                },
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "render .md files, html and latex support",
                fontSize = 15.sp,
                fontStyle = FontStyle.Italic,
                color = colors.tagline,
                letterSpacing = 0.1.sp
            )

            // History
            if (recentFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(48.dp))

                Text(
                    text = "RECENT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = colors.sectionLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recentFiles.forEach { file ->
                        RecentFileRow(file, colors) { openFile(context, file) }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ThemeSwitchButton(
    mode: ThemeMode,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val imageVector = when (mode) {
        ThemeMode.LIGHT -> Icons.Filled.LightMode
        ThemeMode.DARK  -> Icons.Filled.DarkMode
    }
    val label = when (mode) {
        ThemeMode.LIGHT -> "light"
        ThemeMode.DARK  -> "dark"
    }

    Box(
        modifier = modifier
            .semantics { contentDescription = "Switch theme: $label" }
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun RecentFileRow(
    file: RecentFile,
    colors: WelcomeColors,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) colors.rowBgPressed else colors.rowBg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = file.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.rowTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = formatTimestamp(file.timestamp),
            fontSize = 11.sp,
            color = colors.rowTime
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatTimestamp(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000     -> "just now"
        diff < 3_600_000  -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else              -> "${diff / 86_400_000}d ago"
    }
}

private fun openFile(context: android.content.Context, file: RecentFile) {
    context.startActivity(
        Intent(context, ViewerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(ViewerActivity.EXTRA_CACHE_PATH, file.cachePath)
            putExtra(ViewerActivity.EXTRA_ORIGINAL_URI, file.uri)
        }
    )
}
