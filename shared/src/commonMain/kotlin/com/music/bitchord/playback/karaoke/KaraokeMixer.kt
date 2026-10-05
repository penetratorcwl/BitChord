package com.music.bitchord.playback.karaoke

/**
 * Platform-agnostic interface for karaoke/vocal separation control.
 * Implemented per-platform (Android/Desktop) in jvmSharedMain.
 */
interface KaraokeMixer {
    /** Whether karaoke mode is currently active (separation enabled). */
    var isEnabled: Boolean

    /** Vocal gain in dB. 0 dB = original, negative = attenuated, -inf = mute vocal. */
    var vocalGainDb: Float

    /** Instrumental gain in dB. 0 dB = original, negative = attenuated. */
    var instrumentalGainDb: Float

    /** Current separation state. */
    val state: StateFlow<KaraokeState>

    /** Requests separation for the current track. Returns when ready or failed. */
    suspend fun prepare(): Result<Unit>

    /** Releases model resources. */
    fun release()

    /** Sets the karaoke preset (updates gains). */
    fun setPreset(preset: KaraokePreset)

    /** Sets custom vocal gain (0-100%). */
    fun setVocalPercent(percent: Int)
}

/** Current state of karaoke separation. */
sealed interface KaraokeState {
    data object Idle : KaraokeState
    data object Preparing : KaraokeState
    data class Ready(val hasCache: Boolean) : KaraokeState
    data object Separating : KaraokeState
    data class Error(val message: String) : KaraokeState
}

/** Preset gain configurations. */
enum class KaraokePreset(val name: String, val vocalGainDb: Float, val instrumentalGainDb: Float) {
    Original("Original", 0f, 0f),
    Sing("Sing", -10f, 0f),          // Vocal at ~30% (-10dB)
    Instrumental("Instrumental", Float.NEGATIVE_INFINITY, 0f),
    VocalOnly("Vocal Only", 0f, Float.NEGATIVE_INFINITY),
}