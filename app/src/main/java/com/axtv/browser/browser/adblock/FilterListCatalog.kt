package com.axtv.browser.browser.adblock

import com.axtv.browser.AppContext
import org.json.JSONArray

/** A filter list the app knows: [languages] (ISO 639) of the sites of a regional list, empty for the others */
data class FilterListInfo(val id: String, val title: String, val url: String, val languages: Set<String> = emptySet()) {
    val isRegional: Boolean get() = languages.isNotEmpty()
}

/**
 * The filter lists the user can turn on: EasyList, EasyPrivacy and the regional ones, kept in the asset
 * `adblock/lists.json` (an array of `id`, `title`, `url` and the `languages` of a regional one), read once when
 * first asked. The lists of the user's own addresses are not here but in [FilterListsStore].
 */
object FilterListCatalog {
    const val EASYLIST = "easylist"
    const val EASYPRIVACY = "easyprivacy"

    private const val ASSET = "adblock/lists.json"

    /** The lists of the asset, in its order: the first ones are the two every user is asked about */
    val lists: List<FilterListInfo> by lazy { parse(open()) }

    val regional: List<FilterListInfo> get() = lists.filter { it.isRegional }

    fun find(id: String): FilterListInfo? = lists.firstOrNull { it.id == id }

    /** The lists turned on when the user has chosen none yet: EasyList, EasyPrivacy and the list of the [language] */
    fun defaults(language: String): Set<String> =
        setOfNotNull(EASYLIST, EASYPRIVACY, regional.firstOrNull { language in it.languages }?.id)

    /** @throws JSONException when the asset is not an array of objects with an id, a title and a url */
    private fun parse(text: String): List<FilterListInfo> {
        val array = JSONArray(text)
        val result = ArrayList<FilterListInfo>(array.length())
        for (i in 0 until array.length()) {
            val list = array.getJSONObject(i)
            result += FilterListInfo(list.getString("id"), list.getString("title"), list.getString("url"),
                languagesOf(list.optJSONArray("languages")))
        }
        return result
    }

    private fun languagesOf(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val languages = LinkedHashSet<String>(array.length())
        for (i in 0 until array.length()) languages += array.getString(i)
        return languages
    }

    private fun open(): String = AppContext.get().assets.open(ASSET).use { String(it.readBytes(), Charsets.UTF_8) }
}
