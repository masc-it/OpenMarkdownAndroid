package com.mascit.openmarkdown.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecentFile(
    val uri: String,           // original URI (dedup key + display info)
    val title: String,
    val timestamp: Long,
    val cachePath: String      // local cache file path (actual content source)
)

/**
 * Persists recently opened markdown files in SharedPreferences as JSON array.
 * Caches file content locally because external URIs (Telegram, etc.) revoke
 * read permission once the original receiving activity dies.
 *
 * Max 5 entries. Most recent first. Duplicate URI moves to top.
 */
class RecentFilesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheDir: File = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }

    fun list(): List<RecentFile> {
        val json = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val cachePath = obj.optString("cachePath", "")
                // Skip entries whose cache file was deleted (e.g. by Android low-storage cleanup)
                if (cachePath.isEmpty() || !File(cachePath).exists()) return@mapNotNull null
                RecentFile(
                    uri = obj.getString("uri"),
                    title = obj.optString("title", ""),
                    timestamp = obj.getLong("ts"),
                    cachePath = cachePath
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Writes content to cache and persists entry. Returns the saved entry.
     */
    fun push(uri: String, title: String, content: String): RecentFile {
        val cacheFile = File(cacheDir, "${uri.hashCode()}.md")
        cacheFile.writeText(content)

        val entries = list().toMutableList()
        entries.removeAll { it.uri == uri }

        val entry = RecentFile(
            uri = uri,
            title = title,
            timestamp = System.currentTimeMillis(),
            cachePath = cacheFile.absolutePath
        )
        entries.add(0, entry)

        // Trim and delete dropped cache files
        if (entries.size > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size).forEach {
                runCatching { File(it.cachePath).delete() }
            }
        }
        val trimmed = entries.take(MAX_ENTRIES)
        save(trimmed)
        return entry
    }

    private fun save(entries: List<RecentFile>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("uri", e.uri)
                put("title", e.title)
                put("ts", e.timestamp)
                put("cachePath", e.cachePath)
            })
        }
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "openmarkdown_prefs"
        private const val KEY_RECENT = "recent_files"
        private const val CACHE_SUBDIR = "recents"
        private const val MAX_ENTRIES = 5
    }
}
