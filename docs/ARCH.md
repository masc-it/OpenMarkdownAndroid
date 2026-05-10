# Architecture

## Overview

Single-module Android app (`:app`) that renders `.md` files with HTML + LaTeX (KaTeX) support. Two-activity navigation — no Navigation component, no fragments. Rendering pipeline uses WebView + markdown-it 14 + KaTeX with a Kotlin↔JS bridge.

Package: `com.mascit.openmarkdown`

## Activity Graph

```
WelcomeActivity ──(intent)──→ ViewerActivity
    (launcher)                   (file viewer)
```

**WelcomeActivity** (`com.mascit.openmarkdown.WelcomeActivity`):
- Launcher activity. Shows hero text ("# OpenMarkdown") + recent files list.
- Theme toggle cycles through LIGHT → DARK.
- No navigation component — opens ViewerActivity via explicit `Intent`.

**ViewerActivity** (`com.mascit.openmarkdown.ViewerActivity`):
- Accepts intents: `ACTION_VIEW`, `ACTION_EDIT`, `ACTION_SEND`.
- Reads markdown from multiple sources (priority order):
  1. `EXTRA_CACHE_PATH` — cached local copy (from recent files)
  2. `Intent.data` — content/file URI
  3. `Intent.EXTRA_STREAM` — shared file (ACTION_SEND)
  4. `Intent.EXTRA_TEXT` — raw shared text (ACTION_SEND)
- Supports `text/plain` and `text/markdown` mime types.
- `onNewIntent()` handles re-use: updates `pendingMarkdown`, re-parses ToC, re-renders.

## Rendering Pipeline

```
Kotlin reads markdown string
       │
       ▼
MarkdownBridge.push(text)   ← volatile field
       │
       ▼
WebView loads viewer.html (local asset)
       │
       ▼
onPageFinished → renderFromAndroid()
       │
       ▼
JS: AndroidBridge.getContent() → markdown-it.render() → KaTeX post-process → inject DOM
```

### Math Protection (critical detail)

Before markdown-it runs, JS replaces `$$...$$` and `$...$` with invisible placeholders (`\uFFFC` + index + `\uFFFC`). This prevents markdown-it from parsing underscores/asterisks inside LaTeX as emphasis markers. After rendering, placeholders are swapped back with `katex.renderToString()`.

### Welcome screen in WebView

When no content is loaded, `viewer.html` displays a centered welcome message matching WelcomeActivity's hero text. This is the default `#content` innerHTML before `renderMarkdown()` is called.

## JS Bridges (3 interfaces)

All registered via `WebView.addJavascriptInterface()` in `createWebView()`.

| Bridge name | Kotlin class | Methods | Purpose |
|-------------|-------------|---------|---------|
| `AndroidBridge` | `MarkdownBridge` (inner class) | `getContent(): String` | Push markdown from Kotlin to JS. `@Volatile` field, consumed once per render. |
| `Android` | `TocBridge` (inner class) | `onHeadingChanged(index: Int)` | Scroll-tracking callback. JS fires on `scroll` event (passive listener) — reports highest heading above 100px viewport threshold. |
| `Theme` | `ThemeBridge` (inner class) | `getThemeColors(): String` | Returns JSON of CSS custom properties matching M3 color scheme. Injected on page load and theme change. |

### Scroll detection flow

1. `injectHeadingIds()` assigns `id="toc-N"` to every `<h1>`-`<h6>` in rendered content.
2. `updateActiveHeading()` iterates headings bottom→top, finds first with `getBoundingClientRect().top <= 100`, calls `Android.onHeadingChanged(i)`.
3. `ViewerActivity` stores result as `activeHeadingIndex` state — used to highlight active entry in ToC `ModalBottomSheet`.

## Table of Contents

Parsed server-side in Kotlin by `TableOfContents` (`util/TableOfContents.kt`).

### Heading detection

- **ATX headings**: `^#{1,6}\s+(.+)$`
- **Setext headings**: line followed by `^={3,}$` (h1) or `^-{3,}$` (h2)
- Returns list of `TocEntry(level, text, lineNumber)`.

### Title extraction

`extractTitle()` priority:
1. First h1 or h2 text.
2. First sentence from body content (skips headings, code fences, blockquotes). Strips markdown formatting (links, bold, italic, code, strikethrough).
3. `null` — caller falls back to filename.

### UI

- "Contents" button (top of ViewerActivity) opens `ModalBottomSheet` with `LazyColumn`.
- Active heading highlighted using `primaryContainer` background.
- Entry indentation: `(level - 1) * 16.dp`.
- `scrollToHeading(index)` calls JS `document.getElementById('toc-' + n).scrollIntoView()`.

## Recent Files

`RecentFilesStore` (`util/RecentFilesStore.kt`):

- **Storage**: SharedPreferences JSON array under key `recent_files` in prefs file `openmarkdown_prefs`.
- **Cache**: `context.cacheDir/recents/` — file content cached because external URIs (Telegram, etc.) revoke read permission after the receiving activity dies.
- **Max entries**: 5 (`MAX_ENTRIES`). Most-recent-first ordering. Duplicate URI moves to top.
- **Cleanup**: `list()` skips entries whose cache file is missing. `push()` deletes cache files for dropped entries when trimming beyond max.
- **Cache file naming**: `"${uri.hashCode()}.md"`.
- **Data class**: `RecentFile(uri, title, timestamp, cachePath)`.

## Introspection / Scroll Tracking

ViewerActivity uses `activeHeadingIndex` — a `mutableStateOf<Int?>` that updates via the `Android.onHeadingChanged` JS→Kotlin bridge. This state is read by `TocSheetContent` to:
- Scroll the ToC list to the active entry via `listState.scrollToItem()`.
- Apply `primaryContainer` background highlight.
- Set bold weight + primary color for active entry text.

## Asset Dependencies

All vendored in `app/src/main/assets/`:

| File | Version | Purpose |
|------|---------|---------|
| `markdown-it-14.min.js` | 14.x | Markdown → HTML parser |
| `katex.min.js` | (vendored) | LaTeX math rendering |
| `katex.min.css` | (vendored) | KaTeX stylesheet |
| `auto-render.min.js` | (vendored) | Auto-render TeX (loaded but not used — manual render used instead) |
| `mhchem.min.js` | (vendored) | Chemical equation support for KaTeX |
| `mathtex-script-type.min.js` | (vendored) | Alternative math rendering (loaded but not used) |
| `viewer.html` | — | WebView host page with inline JS |

No CDN — no network dependency for rendering.

## Build Config

- AGP 9.2.1, Kotlin 2.2.10, Compose BOM 2026.02.01
- minSdk 26 (Android 8.0), targetSdk 36
- Java 11 source/target compatibility
- No ProGuard in debug. Release has `isMinifyEnabled = false` by default.
- Single module (`:app`), no dynamic features, no product flavors.

## Project Structure

```
app/src/main/java/com/mascit/openmarkdown/
├── WelcomeActivity.kt          # Launcher — hero + recent files
├── ViewerActivity.kt           # Viewer — WebView + JS bridges + ToC sheet
├── ui/theme/
│   ├── Color.kt                # Purple color constants
│   ├── Theme.kt                # OpenMarkdownTheme composable, LocalThemePreference
│   └── Type.kt                 # Typography
└── util/
    ├── RecentFilesStore.kt     # SharedPreferences + cache for recent docs
    ├── TableOfContents.kt      # Heading parser + title extraction
    └── ThemePreference.kt      # ThemeMode enum + preference persistence

app/src/main/assets/
├── viewer.html                 # WebView host page
├── markdown-it-14.min.js       # Vendored markdown parser
├── katex.min.js                # KaTeX renderer
├── katex.min.css               # KaTeX styles
├── auto-render.min.js          # (unused) auto-render addon
├── mhchem.min.js               # Chemical equation extension
└── mathtex-script-type.min.js  # (unused) script-type math addon
```