package com.music.bitchord.data

import com.music.bitchord.data.model.LikeStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Ratings changed during this app session, shared by the UI and playback service.
 *
 * The library remains the source for ratings that were already known at load time;
 * these overrides win over it so a notification tap and a player-screen tap paint
 * the same result immediately.
 */
object LikeState {
    /**
     * How long a tap's own write outranks an answer fetched from the network.
     * A fetch reading the server while a rating write is still landing would
     * otherwise return the pre-tap status and knock the heart the user just
     * saw filled back off again.
     */
    private const val USER_TAP_WINDOW_MS = 5_000L

    private val _overrides = MutableStateFlow<Map<String, LikeStatus>>(emptyMap())
    val overrides: StateFlow<Map<String, LikeStatus>> = _overrides.asStateFlow()

    /** When this session last stated a rating itself, per track. */
    private val userWrittenAt = ConcurrentHashMap<String, Long>()

    fun set(videoId: String, status: LikeStatus) {
        userWrittenAt[videoId] = System.currentTimeMillis()
        _overrides.update { it + (videoId to status) }
    }

    /**
     * Forgets what this session believes about [videoId] rather than stating
     * it is unrated. A pinned `INDIFFERENT` outranks every later page and
     * menu — both [rememberStated] and [seedLiked] defer to an override — so
     * an uncertain verdict written here would stay wrong until the restart.
     */
    fun remove(videoId: String) {
        _overrides.update { if (videoId in it) it - videoId else it }
    }

    /**
     * Records a rating read off a track's own menu, but only when the menu
     * actually states one. A missing like button or an absent rating (null)
     * must never overwrite what this session already knows — kept null
     * rather than INDIFFERENT so the two stay distinct — and an explicit
     * override already made this session always wins.
     */
    fun rememberStated(videoId: String, stated: LikeStatus?) {
        if (stated != null && stated != LikeStatus.INDIFFERENT && videoId !in _overrides.value) {
            set(videoId, stated)
        }
    }

    /**
     * Writes the rating a track's own page states, over anything cached —
     * unlike [rememberStated], an `INDIFFERENT` here is an answer, not an
     * absence of one, and it un-bolds hearts a stale library page seeded.
     * A row that states nothing (null) changes nothing: unknown is not no.
     *
     * This is the authority the walks and disk caches only approximate: it
     * reads the track as YouTube answers for it right now, so a heart is
     * true even when a sync lost its chain somewhere behind.
     */
    fun applyStated(videoId: String, stated: LikeStatus?) {
        if (stated == null) return
        val writtenAt = userWrittenAt[videoId]
        if (writtenAt != null && System.currentTimeMillis() - writtenAt < USER_TAP_WINDOW_MS) return
        _overrides.update { it + (videoId to stated) }
    }

    /**
     * Seeds only ratings not already changed explicitly during this session.
     *
     * The write goes through [update] rather than a read-modify-write of
     * [overrides]: the liked collection lands a page at a time while taps,
     * menus and the player's own seeding write beside it, and a plain
     * "read, merge, compare, publish" publishes nothing at all when a
     * concurrent writer won the race — dropping that page's ids silently.
     */
    fun seedLiked(videoIds: Set<String>) {
        if (videoIds.isEmpty()) return
        _overrides.update { current ->
            val next = current.toMutableMap()
            var changed = false
            for (videoId in videoIds) {
                if (next.putIfAbsent(videoId, LikeStatus.LIKE) == null) changed = true
            }
            if (changed) next else current
        }
    }

    fun clear() {
        _overrides.value = emptyMap()
        userWrittenAt.clear()
    }
}
