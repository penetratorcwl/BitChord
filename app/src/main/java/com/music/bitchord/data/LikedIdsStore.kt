package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The liked video ids of each account, kept across restarts.
 *
 * [LikeState] knows only this session, and filling it means walking the whole
 * Liked Music collection a page at a time — a 2.5k-track library is ~25
 * sequential requests, and a walk cut short by one timeout left every track
 * past that page drawing an unfilled heart, on every cold start, until a
 * lucky walk finished. The ids a walk confirmed live here instead, so a
 * freshly started app paints true hearts before the network gets a word in.
 *
 * Keyed by the listener ([accountId]:[profileId], as MainViewModel's
 * listenerKey builds it): clearing a session's ratings on a switch is right
 * for the screen and wrong for the disk, and the next account's hearts must
 * not inherit the last one's.
 *
 * The store is in memory as a [StateFlow] and on disk as one JSON document
 * written off the main thread, conflated the way [OfflinePlaylists] conflates
 * — a burst of likes is a burst of state updates and at most a couple of
 * writes, each of the newest state.
 */
object LikedIdsStore {
    private const val PREF_NAME = "bitchord_liked_ids"
    private const val KEY = "liked_ids_json"

    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** listener key -> the [videoId]s its last confirmed walk covered. */
    private val _ids = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _ids.value = load()
        val loaded = _ids.value
        io.launch { _ids.filter { it !== loaded }.conflate().collect { persist(it) } }
    }

    fun likedIds(accountKey: String?): Set<String> =
        accountKey?.let { _ids.value[it] } ?: emptySet()

    fun add(accountKey: String?, videoId: String) {
        if (accountKey == null) return
        mutate { stored ->
            stored + (accountKey to ((stored[accountKey] ?: emptySet()) + videoId))
        }
    }

    fun remove(accountKey: String?, videoId: String) {
        if (accountKey == null) return
        mutate { stored ->
            val current = stored[accountKey] ?: return@mutate stored
            stored + (accountKey to (current - videoId))
        }
    }

    /**
     * Records what one walk of the liked collection learned about [accountKey].
     *
     * A [complete] walk read the chain to its end, so its ids are the account
     * as YouTube stated it while the pages were being read — but the reader
     * lags the writer: a tap landed after its page passed is not in what came
     * back, so [likedSince] and [unlikedSince] (this session's confirmed taps
     * since the last complete walk) are spent against it. That replace is the
     * point where stale ids finally leave the store.
     *
     * An incomplete walk proved only its pages. Its ids join what is stored
     * and the rest of the old record survives, because "couldn't read" is not
     * evidence of "not liked" — dropping it instead is how a bad network day
     * would erase a library the account never lost.
     */
    fun recordFetch(
        accountKey: String?,
        ids: Set<String>,
        complete: Boolean,
        likedSince: Set<String>,
        unlikedSince: Set<String>,
    ) {
        if (accountKey == null) return
        mutate { stored ->
            val current = stored[accountKey] ?: emptySet()
            val next = when {
                complete && ids.isNotEmpty() -> (ids - unlikedSince) + likedSince
                else -> current + ids
            }
            stored + (accountKey to next)
        }
    }

    private inline fun mutate(change: (Map<String, Set<String>>) -> Map<String, Set<String>>) {
        synchronized(lock) { _ids.value = change(_ids.value) }
    }

    private fun load(): Map<String, Set<String>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { key ->
                val videos = root.getJSONArray(key)
                (0 until videos.length()).mapTo(HashSet()) { videos.getString(it) }
            }
        }.getOrElse {
            DebugLog.w("BitChord", "Liked ids unreadable: ${it.message}")
            emptyMap()
        }
    }

    private fun persist(ids: Map<String, Set<String>>) {
        runCatching {
            val root = JSONObject()
            ids.forEach { (key, videos) ->
                val array = JSONArray()
                videos.forEach { array.put(it) }
                root.put(key, array)
            }
            prefs.edit().putString(KEY, root.toString()).apply()
        }.onFailure { DebugLog.w("BitChord", "Liked ids not saved: ${it.message}") }
    }
}
