package com.music.bitchord.data

import com.music.bitchord.data.model.LikeStatus
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
    private val _overrides = MutableStateFlow<Map<String, LikeStatus>>(emptyMap())
    val overrides: StateFlow<Map<String, LikeStatus>> = _overrides.asStateFlow()

    fun set(videoId: String, status: LikeStatus) {
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
    }
}
