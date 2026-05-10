# OpenMarkdown

> Android markdown viewer with HTML and LaTeX (KaTeX) rendering.

Render `.md` files from any file manager, share sheet, or app that sends `text/markdown` or `text/plain`. WebView-based rendering with markdown-it 14 + KaTeX, Material 3 theming, and table of contents navigation.

## Features

- Render `.md`, `.markdown` files via `ACTION_VIEW`, `ACTION_SEND`, or `ACTION_EDIT`.
- **LaTeX math** via KaTeX — inline `$...$` and display `$$...$$`.
- **HTML passthrough** — markdown-it runs in `html: true` mode.
- **Table of contents** — auto-detected from ATX (`#`–`######`) and setext headings. Modal bottom sheet with scroll tracking.
- **Recent files** — last 5 files cached locally (survives share permission revocation).
- **Themes** — Light / Dark / System. Material You dynamic color on Android 12+.
- **No network** — all rendering assets vendored locally.

## Screenshots

## Architecture

See [docs/ARCH.md](docs/ARCH.md) for deep dive on:

- Two-activity navigation (no Navigation component)
- WebView + 3 JS bridges (`AndroidBridge`, `TocBridge`, `ThemeBridge`)
- Math protection pipeline (placeholder substitution before markdown-it)
- Two theming systems: Tailwind Slate palette (Welcome) vs M3 dynamic color (Viewer)
- Recent file caching strategy
- Table of contents parsing (ATX + setext)

## Tech Stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose + Material 3 |
| Rendering | WebView + markdown-it 14 + KaTeX |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| Build | Gradle KTS, AGP 9.2.1, Compose BOM 2026.02.01 |

## Project Structure

```
app/src/main/java/com/mascit/openmarkdown/
├── WelcomeActivity.kt         Launcher — hero + recent files list
├── ViewerActivity.kt          Viewer — WebView + JS bridges + ToC sheet
├── ui/theme/
│   ├── Color.kt               Color constants
│   ├── Theme.kt               OpenMarkdownTheme + LocalThemePreference
│   └── Type.kt                Typography
└── util/
    ├── RecentFilesStore.kt    Persistence for recent docs (SharedPrefs + file cache)
    ├── TableOfContents.kt     Heading parser + title extraction
    └── ThemePreference.kt     ThemeMode enum + preference persistence

app/src/main/assets/
├── viewer.html                WebView host page (inline JS for render pipeline)
├── markdown-it-14.min.js      Vendored markdown parser
├── katex.min.js               KaTeX renderer
├── katex.min.css              KaTeX stylesheet
├── auto-render.min.js         (unused) auto-render addon
├── mhchem.min.js              KaTeX chemistry extension
└── mathtex-script-type.min.js (unused) script-type math addon
```
