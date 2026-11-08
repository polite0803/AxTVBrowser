package com.axtv.browser.browser.adblock

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** What the app knows of a list: its updates and what the compiler found in it */
class ListMeta(
    /** When the list was last downloaded or found unchanged; 0: never */
    var lastSuccess: Long = 0,
    /** When the last download failed (with [failures] in a row) */
    var lastAttempt: Long = 0,
    var failures: Int = 0,
    var etag: String? = null,
    var lastModified: String? = null,
    /** How often the list asks to be updated (its `! Expires:`) */
    var expiresHours: Int? = null,
    /** The request filters the engine has of it, and the filters it skipped */
    var filters: Int = 0,
    var skipped: Int = 0,
)

/** A filter list of the user's own: [id] names its file, [url] is where it comes from and what the settings show */
data class CustomList(val id: String, val url: String)

/**
 * The lists the user turned on, the lists of their own addresses, and the [ListMeta] of each list, kept in a
 * JSON file written atomically. Not thread-safe: [AdBlockService] uses it under its lock.
 */
class FilterListsStore(private val file: File, private val language: String) {
    val enabled = LinkedHashSet<String>()
    /** The lists the user gave the addresses of, in the order they were added */
    val custom = ArrayList<CustomList>()
    private val metas = HashMap<String, ListMeta>()

    fun meta(id: String): ListMeta = metas.getOrPut(id) { ListMeta() }

    fun forget(id: String) {
        metas.remove(id)
    }

    fun customOf(id: String): CustomList? = custom.firstOrNull { it.id == id }

    /** Adds the list of the [url] and turns it on; @return its id, null when a list of that address is there already */
    fun addCustom(url: String): String? {
        if (url.isEmpty() || custom.any { it.url == url }) return null
        val id = idOf(url)
        custom += CustomList(id, url)
        enabled += id
        return id
    }

    /** Removes the list of [id] and its place in the turned on lists; @return false when there is no such list */
    fun removeCustom(id: String): Boolean {
        if (!custom.removeAll { it.id == id }) return false
        enabled.remove(id)
        return true
    }

    /** The lists to compile, in a fixed order: the catalog ones turned on, then the user's own */
    fun enabledSources(): List<FilterListInfo> {
        val sources = FilterListCatalog.lists.filter { it.id in enabled }.toMutableList()
        for (list in custom) if (list.id in enabled) sources += FilterListInfo(list.id, list.url, list.url)
        return sources
    }

    fun load() {
        enabled.clear()
        custom.clear()
        metas.clear()
        val json = try {
            if (file.exists()) JSONObject(file.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "Can not read $file", e)
            null
        }
        if (json == null) {
            enabled += FilterListCatalog.defaults(language)
            return
        }
        try {
            val ids = json.getJSONArray("enabled")
            for (i in 0 until ids.length()) enabled += ids.getString(i)
            val customLists = json.optJSONArray("customLists")
            if (customLists != null) {
                for (i in 0 until customLists.length()) {
                    val list = customLists.getJSONObject(i)
                    custom += CustomList(list.getString("id"), list.getString("url"))
                }
            } else {
                //a version with a single list of the user's own keeps its file and its meta under [LEGACY_ID]
                val legacy = json.optString("customUrl")
                if (legacy.isNotEmpty()) {
                    custom += CustomList(LEGACY_ID, legacy)
                    enabled += LEGACY_ID
                }
            }
            val lists = json.optJSONObject("lists") ?: JSONObject()
            for (id in lists.keys()) {
                val m = lists.getJSONObject(id)
                metas[id] = ListMeta(
                    lastSuccess = m.optLong("lastSuccess"), lastAttempt = m.optLong("lastAttempt"),
                    failures = m.optInt("failures"), etag = m.optString("etag").ifEmpty { null },
                    lastModified = m.optString("lastModified").ifEmpty { null },
                    expiresHours = if (m.has("expiresHours")) m.getInt("expiresHours") else null,
                    filters = m.optInt("filters"), skipped = m.optInt("skipped"),
                )
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Bad $file", e)
            enabled.clear()
            custom.clear()
            metas.clear()
            enabled += FilterListCatalog.defaults(language)
        }
    }

    fun save() {
        val lists = JSONObject()
        for ((id, m) in metas) {
            lists.put(id, JSONObject()
                .put("lastSuccess", m.lastSuccess).put("lastAttempt", m.lastAttempt).put("failures", m.failures)
                .put("etag", m.etag ?: "").put("lastModified", m.lastModified ?: "")
                .put("filters", m.filters).put("skipped", m.skipped)
                .apply { m.expiresHours?.let { put("expiresHours", it) } })
        }
        val customLists = JSONArray()
        for (list in custom) customLists.put(JSONObject().put("id", list.id).put("url", list.url))
        val json = JSONObject().put("enabled", JSONArray(enabled.toList())).put("customLists", customLists)
            .put("lists", lists)
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use {
                it.write(json.toString().toByteArray())
                it.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("can not rename $temp")
        } catch (e: IOException) {
            Log.e(TAG, "Can not write $file", e)
        }
    }

    companion object {
        /** The id of the single list of the user's own of the older versions: it keeps its file and its meta */
        const val LEGACY_ID = "custom"

        /** The id of the list of an [url], and the name of its file: the same address keeps its file */
        fun idOf(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(12)
            for (i in 0 until 6) {
                val byte = digest[i].toInt()
                hex.append(HEX[byte shr 4 and 0xF]).append(HEX[byte and 0xF])
            }
            return "custom-$hex"
        }

        private const val TAG = "FilterListsStore"
        private const val HEX = "0123456789abcdef"
    }
}
