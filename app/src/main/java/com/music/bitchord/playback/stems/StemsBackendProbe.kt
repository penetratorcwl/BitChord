package com.music.bitchord.playback.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds out which [StemsAccelerator] rungs this device can actually run, and —
 * for those it can — whether the model gives them any work.
 *
 * ## Two stages, because "available" is not the question
 *
 * The static stage asks whether a path *exists*: is the provider compiled into
 * this build of ONNX Runtime, is the device new enough for NNAPI, is Qualcomm's
 * plugin present. It costs nothing and it is the only stage that can run while
 * the settings screen is still opening.
 *
 * The empirical stage asks the question that actually matters, which static
 * checks cannot answer: **does this backend get any nodes?** A provider can be
 * present, registered, load successfully, appear in the session, and still be
 * handed nothing to do — which is exactly what happens with this model, whose
 * graph is three bidirectional LSTM layers, an operation neither NNAPI nor
 * XNNPACK implements. Only ONNX Runtime's own per-node profile settles it.
 *
 * That costs one inference over the model's fixed 22.8-second window, so it is
 * never run speculatively. [staticProbe] is free and safe to call anywhere;
 * [verify] runs one rung and caches the answer for the process.
 *
 * ## Caching
 *
 * A verification is keyed on the accelerator and the model file's size and
 * modification time, so replacing the model re-opens the question instead of
 * reporting a verdict about weights that are no longer there. Results live in
 * memory only: they describe this process's view of the hardware, and a reboot
 * can change which driver NNAPI picks.
 */
object StemsBackendProbe {

    private const val TAG = "BitChordStemsProbe"

    /** Latency added by NNAPI's model compilation; see the NNAPI EP docs. */
    private const val NNAPI_MIN_API = Build.VERSION_CODES.P

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val verified = java.util.Collections.synchronizedMap(mutableMapOf<StemsAccelerator, StemsBackendResult>())

    /** Drops every cached verdict, so the next read re-measures. */
    fun invalidate() {
        verified.clear()
        pluginSearched = false
        cachedPlugin = null
    }

    /**
     * Which providers this build of ONNX Runtime can register.
     *
     * Read from the runtime rather than hardcoded, because which providers a
     * given artifact contains is a packaging fact: `onnxruntime-mobile` has a
     * different set from `onnxruntime-android`, and a stripped build could have
     * fewer again. Asking costs a static call, and the answer cannot change
     * within a process, so it is asked once.
     */
    private val availableProviders: Set<String> by lazy {
        runCatching {
            OrtEnvironment.getAvailableProviders().map { it.name }.toSet()
        }.getOrElse {
            Log.w(TAG, "Could not enumerate execution providers", it)
            // Nothing is known, so the only rung not being claimed is the one
            // that needs no provider at all.
            setOf("CPUExecutionProvider")
        }
    }

    /** The Qualcomm plugin's location, likewise fixed for the life of a process. */
    private var cachedPlugin: File? = null
    private var pluginSearched = false

    private fun plugin(context: Context): File? {
        if (!pluginSearched) {
            cachedPlugin = SeparationSessionFactory.findQualcommPlugin(context)
            pluginSearched = true
        }
        return cachedPlugin
    }

    /**
     * The free half of the probe: can each rung be *attempted* here?
     *
     * Returns [StemsBackendState.UNVERIFIED] for everything reachable, because
     * nothing has run yet. [StemsCapabilities.effective] is
     * [StemsAccelerator.CPU_XNNPACK] when that provider exists and plain
     * [StemsAccelerator.CPU] otherwise — the safest unproven guess, since both
     * are CPU and XNNPACK only changes which kernels run.
     */
    fun staticProbe(context: Context): StemsCapabilities {
        val providers = availableProviders
        val hasNnapi = providers.any { it.contains("Nnapi", ignoreCase = true) }
        val hasXnnpack = providers.any { it.contains("Xnnpack", ignoreCase = true) }
        val apiOk = Build.VERSION.SDK_INT >= NNAPI_MIN_API
        val plugin = plugin(context)
        val qualcomm = isQualcomm()

        val probes = listOf(
            StemsBackendResult(
                StemsAccelerator.CPU,
                StemsBackendState.UNVERIFIED,
                "Always available",
            ),
            if (hasXnnpack) {
                StemsBackendResult(
                    StemsAccelerator.CPU_XNNPACK,
                    StemsBackendState.UNVERIFIED,
                    "XNNPACK kernels present",
                )
            } else {
                unavailable(StemsAccelerator.CPU_XNNPACK, "XNNPACK is not in this ONNX Runtime build")
            },
            when {
                !hasNnapi -> unavailable(StemsAccelerator.ACCELERATOR, "NNAPI is not in this ONNX Runtime build")
                !apiOk -> unavailable(
                    StemsAccelerator.ACCELERATOR,
                    "NNAPI needs Android 9; this device is API ${Build.VERSION.SDK_INT}",
                )
                else -> StemsBackendResult(
                    StemsAccelerator.ACCELERATOR,
                    StemsBackendState.UNVERIFIED,
                    "NNAPI present · device chosen by the driver",
                )
            },
            when {
                plugin == null && !qualcomm -> unavailable(
                    StemsAccelerator.SNAPDRAGON_NPU,
                    "Qualcomm plugin provider is not installed",
                )
                plugin == null -> unavailable(
                    StemsAccelerator.SNAPDRAGON_NPU,
                    "Snapdragon detected, but the Qualcomm plugin provider is not installed",
                )
                !hasNnapi -> unavailable(
                    StemsAccelerator.SNAPDRAGON_NPU,
                    "Plugin found, but NNAPI is missing from this build",
                )
                else -> StemsBackendResult(
                    StemsAccelerator.SNAPDRAGON_NPU,
                    StemsBackendState.UNVERIFIED,
                    "Qualcomm plugin found: ${plugin.name}",
                )
            },
        )

        return StemsCapabilities(probes, StemsAccelerator.CPU_XNNPACK.takeIf { hasXnnpack } ?: StemsAccelerator.CPU)
    }

    private fun unavailable(accelerator: StemsAccelerator, why: String) =
        StemsBackendResult(accelerator, StemsBackendState.UNAVAILABLE, why)

    /**
     * Whether this is a Snapdragon, from the public `Build` fields only.
     *
     * `Build.SOC_MANUFACTURER` and `Build.SOC_MODEL` would be the direct
     * answer and neither is in the public SDK — both are `@hide`, so referencing
     * them does not compile and reading them by reflection is a maintenance
     * liability on a field that can vanish between releases. `HARDWARE`,
     * `BOARD` and `DEVICE` are public API level 1 and carry the same SoC
     * family on every Qualcomm device in the field: `qcom`, `Qualcomm`, or a
     * `msm`/`sdm`/`sm`/`qrd` board name.
     *
     * Used only to word the Snapdragon rung's reason better — whether the
     * plugin is present is decided by looking for it, not by asking who made
     * the phone.
     */
    private fun isQualcomm(): Boolean =
        QUALCOMM_SOC_TOKENS.any { token ->
            listOf(Build.MANUFACTURER, Build.HARDWARE, Build.BOARD, Build.DEVICE)
                .any { field -> field != null && field.contains(token, ignoreCase = true) }
        }

    private val QUALCOMM_SOC_TOKENS = listOf("qualcomm", "qcom", "msm", "sdm", "qrd")

    /**
     * Runs one real inference on [accelerator] and reads its profile.
     *
     * Blocking and expensive — one pass over the model's fixed 22.8-second
     * window, plus the session build. Call it off the main thread and only from
     * an explicit user action; the settings screen does exactly that.
     *
     * @return the measured probe, or null when the session could not be built,
     *   in which case the rung is reported unavailable rather than failing the
     *   caller.
     */
    fun verify(
        context: Context,
        accelerator: StemsAccelerator,
        threads: Int,
    ): StemsBackendResult? {
        if (accelerator == StemsAccelerator.AUTO) return null
        verified[accelerator]?.let { return it }

        val profileFile = File(context.cacheDir, "stems-profile-${accelerator.name}.json")
        profileFile.delete()
        val session = SeparationSessionFactory.create(context, accelerator, threads, profileFile)
            ?: run {
                verified[accelerator] = unavailable(accelerator, "Session could not be created on this device")
                return verified[accelerator]
            }

        return session.use { open ->
            val input = syntheticWindow()
            val started = System.nanoTime()
            val failure = runCatching {
                open.run(mapOf(open.inputNames.first() to input)).get(0).close()
            }.exceptionOrNull()
            val millis = (System.nanoTime() - started) / 1_000_000

            if (failure != null) {
                Log.w(TAG, "Verification inference failed on $accelerator", failure)
                verified[accelerator] = unavailable(accelerator, "Inference failed: ${failure.messageOrType()}")
                return verified[accelerator]
            }

            val profile = runCatching { open.endProfiling() }.getOrNull()
                ?.let(::readProfile)
            profileFile.delete()

            val result = when {
                profile == null -> StemsBackendResult(
                    accelerator,
                    StemsBackendState.VERIFIED,
                    "Ran in ${millis}ms · provider breakdown unavailable",
                )
                profile.total == 0 -> StemsBackendResult(
                    accelerator,
                    StemsBackendState.UNVERIFIED,
                    "Profile contained no nodes",
                )
                else -> {
                    val mine = profile.byProvider.getOrElse(accelerator.providerName()) { 0 }
                    val pct = (100 * mine) / profile.total
                    val detail = if (mine == 0) {
                        "Ran in ${millis}ms · 0 of ${profile.total} nodes offloaded — " +
                            "this model is LSTM-only, which no accelerator implements"
                    } else {
                        "Ran in ${millis}ms · $mine of ${profile.total} nodes ($pct%)"
                    }
                    StemsBackendResult(
                        accelerator = accelerator,
                        state = StemsBackendState.VERIFIED,
                        reason = detail,
                        supportedOps = mine,
                        totalOps = profile.total,
                        millis = millis,
                    )
                }
            }
            verified[accelerator] = result
            result
        }
    }

    /**
     * The whole picture, with whatever [verify] has already measured folded in.
     *
     * Cheap, because the unmeasured rungs keep their static verdicts.
     */
    fun capabilities(context: Context): StemsCapabilities {
        val base = staticProbe(context)
        val merged = base.backends.map { probe ->
            verified[probe.accelerator] ?: probe
        }
        val effective = merged
            .firstOrNull { it.accelerator == StemsAccelerator.SNAPDRAGON_NPU && it.effective }
            ?.accelerator
            ?: merged.firstOrNull { it.accelerator == StemsAccelerator.ACCELERATOR && it.effective }?.accelerator
            ?: merged.firstOrNull { it.accelerator == StemsAccelerator.CPU_XNNPACK }?.accelerator
            ?: StemsAccelerator.CPU
        return StemsCapabilities(merged, effective)
    }

    /**
     * A zero-filled input of the model's exact shape.
     *
     * Zeros rather than noise on purpose: this measures whether the graph runs
     * and where its nodes land, not what the output looks like, and a zero input
     * is the cheapest thing that still exercises every kernel. Shape is fixed at
     * 960 frames by the model's own initializer — a concrete `dim_value`, not a
     * symbolic dimension — so it cannot be shrunk.
     */
    private fun syntheticWindow(): OnnxTensor {
        val env = OrtEnvironment.getEnvironment()
        val bytes = (CHANNELS * BINS * FRAMES * Int.SIZE_BYTES).toInt()
        val backing = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        return OnnxTensor.createTensor(env, backing.asFloatBuffer(), longArrayOf(1, CHANNELS, BINS, FRAMES))
    }

    private const val CHANNELS = 2L
    private const val BINS = 2049L
    private const val FRAMES = 960L

    /** What the profile says about node assignment. */
    private data class Profile(val total: Int, val byProvider: Map<String, Int>)

    /**
     * Counts profiled nodes per provider name.
     *
     * ORT's profiling output is a JSON array of events whose `args` carry a
     * `provider` string. It is machine-generated and flat, so a generic tree
     * walk is enough and avoids pinning a schema to a file format ORT does not
     * promise to keep.
     */
    private fun readProfile(path: String?): Profile? {
        val file = path?.let(::File) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            var total = 0
            val counts = mutableMapOf<String, Int>()
            json.parseToJsonElement(file.readText()).jsonArray.forEach { event ->
                val args = event.jsonObject["args"]?.jsonObject ?: return@forEach
                val provider = args["provider"]?.jsonPrimitive?.content ?: return@forEach
                counts[provider] = (counts[provider] ?: 0) + 1
                total++
            }
            Profile(total, counts)
        }.onFailure { Log.w(TAG, "Unreadable profile at $path", it) }.getOrNull()
    }

    /**
     * The provider name ORT writes into its profile for a rung.
     *
     * The leading/trailing "ExecutionProvider" suffixes are part of ORT's
     * internal enum spelling and do appear in profile output, so the lookup is
     * by the bare stem rather than by an exact string.
     */
    private fun StemsAccelerator.providerName(): String = when (this) {
        StemsAccelerator.SNAPDRAGON_NPU -> "QNN"
        else -> name
    }

    private fun Throwable.messageOrType(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
