package com.mascit.openmarkdown.util

data class TocEntry(
    val level: Int,
    val text: String,
    val lineNumber: Int
)

/**
 * Parses markdown heading structure.
 *
 * Extracts ATX headings (# through ######) plus setext headings
 * (underlined with === or ---). Used for navigation and document title extraction.
 *
 * Title extraction priority:
 *   1. First h1 or h2 text
 *   2. First sentence from body content (markdown stripped)
 *   3. null (caller falls back to filename)
 */
class TableOfContents private constructor(
    val entries: List<TocEntry>
) {
    companion object {
        private val ATX_HEADING = Regex("^(#{1,6})\\s+(.+)$")
        private val SETEXT_UNDERLINE = Regex("^(={3,}|-{3,})$")

        fun parse(markdown: String): TableOfContents {
            if (markdown.isBlank()) return TableOfContents(emptyList())

            val lines = markdown.lines()
            val entries = mutableListOf<TocEntry>()

            var i = 0
            while (i < lines.size) {
                val line = lines[i]

                // ATX heading: # through ######
                val atxMatch = ATX_HEADING.find(line)
                if (atxMatch != null) {
                    val level = atxMatch.groupValues[1].length
                    val text = atxMatch.groupValues[2].trim()
                    entries.add(TocEntry(level, text, i))
                    i++
                    continue
                }

                // Setext heading: line underlined with === or ---
                if (i + 1 < lines.size) {
                    val next = lines[i + 1]
                    val setextMatch = SETEXT_UNDERLINE.matchEntire(next)
                    if (setextMatch != null && line.isNotBlank()) {
                        val level = if (setextMatch.groupValues[1] == "=") 1 else 2
                        val text = line.trim()
                        entries.add(TocEntry(level, text, i))
                        i += 2
                        continue
                    }
                }

                i++
            }

            return TableOfContents(entries)
        }
    }

    /**
     * Returns first h1 or h2 text as document title.
     * Fallback: first non-empty sentence from body content.
     * Returns null if nothing found.
     */
    fun extractTitle(markdown: String): String? {
        // Prefer first h1 or h2
        for (entry in entries) {
            if (entry.level <= 2 && entry.text.isNotBlank()) {
                return entry.text
            }
        }

        // Fallback: first sentence from body (skip headings, code fences, blockquotes)
        val lines = markdown.lines()
        val headingLines = entries.map { it.lineNumber }.toSet()
        var inCodeBlock = false

        for ((i, line) in lines.withIndex()) {
            if (i in headingLines) continue
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock) continue
            if (line.isBlank()) continue
            if (line.trimStart().startsWith(">")) continue

            val stripped = stripMarkdown(line).trim()
            if (stripped.isBlank()) continue

            return extractFirstSentence(stripped) ?: stripped.take(120)
        }

        return null
    }

    private fun extractFirstSentence(text: String): String? {
        var idx = -1
        for ((i, c) in text.withIndex()) {
            if (c in ".!?") {
                // Avoid matching decimal dots in numbers
                if (c == '.' && i > 0 && text[i - 1].isDigit()) continue
                idx = i
                break
            }
        }
        return if (idx >= 0) text.substring(0, idx + 1).trim() else null
    }

    private fun stripMarkdown(text: String): String {
        // Order matters: strip images/links first before they interfere with other patterns
        return text
            .replace(Regex("!\\[[^]]*]\\([^)]+\\)"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\*{3}(.+?)\\*{3}"), "$1")
            .replace(Regex("\\*{2}(.+?)\\*{2}"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("~~(.+?)~~"), "$1")
            .trim()
    }
}
