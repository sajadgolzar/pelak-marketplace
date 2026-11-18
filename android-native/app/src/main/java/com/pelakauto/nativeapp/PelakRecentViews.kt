package com.pelakauto.nativeapp

import android.content.Context
import org.json.JSONArray

/**
 * Device-local recent listing IDs. No sensitive listing details are stored.
 * Each signed-in account has its own history; guest browsing uses a separate key.
 */
internal class PelakRecentViews(context: Context) {
    private val prefs = context.getSharedPreferences("pelak_recent_views", Context.MODE_PRIVATE)

    private fun key(userId: String?): String = "recent_" + (userId ?: "guest")

    fun ids(userId: String?): List<String> {
        val raw = prefs.getString(key(userId), "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
                .filter { it.matches(Regex("[0-9a-fA-F-]{36}")) }
                .distinct().take(50)
        }.getOrDefault(emptyList())
    }

    fun record(userId: String?, listingId: String) {
        if (!listingId.matches(Regex("[0-9a-fA-F-]{36}"))) return
        val updated = (listOf(listingId) + ids(userId)).distinct().take(50)
        prefs.edit().putString(key(userId), JSONArray(updated).toString()).apply()
    }

    fun clear(userId: String?) {
        prefs.edit().remove(key(userId)).apply()
    }
}
