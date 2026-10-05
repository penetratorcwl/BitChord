package com.music.bitchord.playback.karaoke

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.withContext

/**
 * Android implementation of KaraokeMixer.
 * Handles offline vocal separation, caching, and real-time mixing during playback.
 */
@UnstableApi
class KaraokeMixerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getCurrentMediaItem: () -> MediaItem?,
    private val getUpstreamDataSource: () -> DataSource.Factory?,
) : KaraokeMixer, FloatAudioProcessor {

    private val vocalSeparator = VocalSeparator(context, scope)
    private var separationJob: Job? = null
    private var currentMediaId: String? = null
    private var instrumentalCacheKey: String? = null
    private var instrumentalData: FloatArray? = null
    private var instrumentalSampleRate = 0
    private var instrumentalChannels = 0
    private var instrumentalDurationUs = 0L
    private var readPositionUs = 0L

    // Gain control (applied in process())
    override var isEnabled: Boolean = false
        private set

    override var vocalGainDb: Float = 0f
        private set

    override var instrumentalGainDb: Float = 0f
        private set

    private val _state = MutableStateFlow<KaraokeState>(KaraokeState.Idle)
    override val state: StateFlow<KaraokeState> = _state.asStateFlow()

    private var configuredSampleRate = 0
    private var configuredChannels = 0

    override suspend fun prepare(): Result<Unit> {
        val item = getCurrentMediaItem() ?: return Result.failure(IllegalStateException("No media item"))
        val mediaId = item.mediaId
        if (mediaId == currentMediaId && instrumentalData != null) {
            _state.value = KaraokeState.Ready(true)
            return Result.success(Unit)
        }

        separationJob?.cancel()
        _state.value = KaraokeState.Preparing

        return withContext(Dispatchers.IO) {
            separationJob = scope.launch(Dispatchers.IO) {
                separateAndCache(item)
            }
            separationJob?.join()
            Result.success(Unit)
        }.onFailure {
            _state.value = KaraokeState.Error(it.message ?: "Preparation failed")
            Result.failure(it)
        }.getOrNull() ?: Result.failure(IllegalStateException("Preparation cancelled"))
    }

    private fun separateAndCache(item: MediaItem) {
        val mediaId = item.mediaId
        currentMediaId = mediaId
        instrumentalCacheKey = "karaoke_inst_$mediaId"

        // Check cache first
        if (loadInstrumentalFromCache()) {
            _state.value = KaraokeState.Ready(true)
            return
        }

        _state.value = KaraokeState.Separating

        // Fetch full audio for separation
        val dataSource = getUpstreamDataSource()?.createDataSource()
            ?: throw IllegalStateException("No upstream data source")
        val uri = item.localConfiguration?.uri ?: throw IllegalStateException("No URI")

        try {
            dataSource.open(DataSpec(uri))
            val totalLength = dataSource.open(DataSpec(uri))
            if (totalLength <= 0) throw IOException("Empty track")

            // Read all audio (simplified - real impl would stream)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.nativeOrder())
            val leftChunks = mutableListOf<FloatArray>()
            val rightChunks = mutableList<FloatArray>()
            var totalSamples = 0L

            while (true) {
                val read = dataSource.read(buffer.array(), 0, buffer.capacity())
                if (read <= 0) break
                // Decode audio (simplified - assumes stereo float32)
                // Real impl: use ExoPlayer's decoder or FFmpeg
                // For now, placeholder
                totalSamples += read / 4 / 2
            }
            dataSource.close()

            // TODO: Proper audio decoding and separation
            // This is a scaffold - the actual separation needs decoded PCM

            _state.value = KaraokeState.Error("Audio decoding not yet implemented")
        } catch (e: Exception) {
            Log.e(TAG, "Separation failed", e)
            _state.value = KaraokeState.Error(e.message ?: "Separation failed")
        }
    }

    private fun loadInstrumentalFromCache(): Boolean {
        // Check AudioCache for instrumental rendition
        val key = instrumentalCacheKey ?: return false
        // AudioCache doesn't expose direct key lookup easily
        // We'd need to extend it or use a separate cache
        return false
    }

    private fun saveInstrumentalToCache(): Boolean {
        // Save instrumental as a separate rendition in AudioCache
        // Key: "karaoke_inst_$mediaId"
        return false
    }

    override fun release() {
        separationJob?.cancel()
        vocalSeparator.release()
        instrumentalData = null
    }

    // FloatAudioProcessor implementation
    override fun configure(sampleRate: Int, channelCount: Int) {
        configuredSampleRate = sampleRate
        configuredChannels = channelCount
        readPositionUs = 0L
    }

    override fun process(block: AudioBlock) {
        if (!isEnabled || instrumentalData == null) return
        if (block.frameCount == 0 || configuredChannels < 2) return

        // Mix: output = original * vocalGain + instrumental * instrumentalGain
        // vocalGain = 10^(vocalGainDb/20), instrumentalGain = 10^(instrumentalGainDb/20)
        val vocalGain = if (vocalGainDb <= -80f) 0f else 10f.pow(vocalGainDb / 20f)
        val instGain = if (instrumentalGainDb <= -80f) 0f else 10f.pow(instrumentalGainDb / 20f)

        val samples = block.data
        val frameCount = block.frameCount
        val channels = block.channelCount

        // Read instrumental samples (loop if needed)
        var instPos = (readPositionUs * configuredSampleRate / 1_000_000).toInt() * channels
        val instLen = instrumentalData!!.size

        for (i in 0 until frameCount * channels step channels) {
            val origL = samples[i]
            val origR = samples[i + 1]

            val instL = if (instPos < instLen) instrumentalData!![instPos] else 0f
            val instR = if (instPos + 1 < instLen) instrumentalData!![instPos + 1] else 0f

            // Mix: vocal = orig - inst (approximate), but we have cached instrumental
            // So: output = orig * vocalGain + inst * instGain
            // But orig = vocal + inst, so vocal = orig - inst
            // output = (orig - inst) * vocalGain + inst * instGain
            //        = orig * vocalGain + inst * (instGain - vocalGain)
            samples[i] = origL * vocalGain + instL * (instGain - vocalGain)
            samples[i + 1] = origR * vocalGain + instR * (instGain - vocalGain)

            instPos += channels
            if (instPos >= instLen) instPos = 0
        }

        readPositionUs += (frameCount * 1_000_000L) / configuredSampleRate
    }

    override fun flush() {
        readPositionUs = 0L
    }

    override fun reset() {
        readPositionUs = 0L
    }

    /** Sets the karaoke preset (updates gains). */
    fun setPreset(preset: KaraokePreset) {
        vocalGainDb = preset.vocalGainDb
        instrumentalGainDb = preset.instrumentalGainDb
        isEnabled = preset != KaraokePreset.Original
    }

    /** Sets custom vocal gain (0-100%). */
    fun setVocalPercent(percent: Int) {
        val p = percent.coerceIn(0, 100) / 100f
        vocalGainDb = if (p <= 0f) Float.NEGATIVE_INFINITY else 20f * kotlin.math.log10(p)
        isEnabled = percent != 100
    }

    companion object {
        private const val TAG = "KaraokeMixer"
    }
}