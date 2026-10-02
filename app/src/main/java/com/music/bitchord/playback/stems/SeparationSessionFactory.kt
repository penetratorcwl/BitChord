package com.music.bitchord.playback.stems

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.util.EnumSet

/**
 * Builds the vocal-separation [OrtSession] for a chosen [StemsAccelerator].
 *
 * ## Why registration lives here and not in the probe
 *
 * [StemsBackendProbe] needs to *run* an inference to know whether a rung works,
 * which means it needs a session, and the two would otherwise both be writing
 * the same five lines of `SessionOptions`. So this owns registration and the
 * probe drives it.
 *
 * ## Why every rung returns rather than throws
 *
 * A missing execution provider is an ordinary outcome here — most Android
 * devices cannot do three of the four — and it has to be reported to a settings
 * screen, not thrown at it. So creation failures come back as null with a logged
 * reason and the caller's own fallback list takes over. The vocaller's fallback
 * is plain CPU, which cannot fail for want of hardware.
 */
object SeparationSessionFactory {

    private const val TAG = "BitChordStems"

    /** Model asset copied out of the APK on first use — ONNX Runtime needs a real file. */
    const val MODEL_ASSET = "vocals_umxhq_int8.onnx"

    /**
     * Where a Qualcomm plugin execution provider might be found, most specific
     * first.
     *
     * ONNX Runtime loads plugin EPs from a `.so` at runtime through
     * `registerExecutionProviderLibrary`, which is why the Snapdragon rung can
     * exist at all in a build that does not itself contain a QNN provider. The
     * name is **not** fixed by anything: Qualcomm's plugin EP is versioned and
     * renamed, and this list is a discovery heuristic, not a specification. If
     * a Snapdragon device reports the rung unavailable while a plugin is
     * installed, this is the list to extend — the entry point below is the other
     * thing that has to match.
     */
    private val PLUGIN_CANDIDATES = listOf(
        "libqnn_ep_plugin.so",
        "libQnnEpPlugin.so",
        "libqnnplugin_ep.so",
        "libQnnEpPlugin_v2.so",
    )

    /**
     * Entry point ORT calls inside the plugin library.
     *
     * Same caveat as [PLUGIN_CANDIDATES]: it follows Qualcomm's naming, not
     * anything ONNX Runtime guarantees. A mismatch surfaces as an
     * `OrtException` from registration, which [StemsBackendProbe] turns into an
     * unavailable rung with the message attached rather than a crash.
     */
    private const val PLUGIN_ENTRY_POINT = "RegisterExecutionProvider"

    /**
     * Qualcomm's plugin library if one is present on the device, or null.
     *
     * Only the app's own native library directory is searched. Qualcomm
     * distribute the plugin as part of the QNN SDK rather than as something an
     * app ships, so on a stock device this returns null and the Snapdragon rung
     * is unavailable — which is the correct answer, not a packaging bug.
     */
    fun findQualcommPlugin(context: Context): File? {
        val roots = buildList {
            runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
                ?.let(::add)
            runCatching { File(context.filesDir, PLUGIN_DIR).absolutePath }.getOrNull()
                ?.let(::add)
        }
        for (root in roots) {
            for (name in PLUGIN_CANDIDATES) {
                val candidate = File(root, name)
                if (candidate.exists() && candidate.length() > 0L) return candidate
            }
        }
        return null
    }

    /**
     * Registers the plugin EP for the whole environment, at most once per path.
     *
     * Registration is process-global and idempotent per library; registering
     * twice throws, so the set is what makes a second call harmless.
     */
    private val registeredPlugins = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** True when the Qualcomm plugin is registered and therefore selectable. */
    fun registerQualcommPlugin(context: Context): Result<File> =
        runCatching {
            val library = findQualcommPlugin(context)
                ?: error("no Qualcomm plugin library found on device")
            if (registeredPlugins.add(library.absolutePath)) {
                OrtEnvironment.getEnvironment()
                    .registerExecutionProviderLibrary(library.absolutePath, PLUGIN_ENTRY_POINT)
                Log.i(TAG, "Registered Qualcomm plugin EP from ${library.name}")
            }
            library
        }

    /**
     * Ensures the model file exists outside the APK and returns it.
     *
     * Public because the probe reports on a real model file and ONNX Runtime
     * opens one by path.
     */
    fun modelFile(context: Context): File {
        val target = File(context.filesDir, MODEL_ASSET)
        if (target.exists() && target.length() > 0L) return target
        context.assets.open(MODEL_ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    /**
     * Creates a session on [accelerator], or null if that rung cannot be
     * registered on this device.
     *
     * @param threads intra-op thread count. Left at ORT's default rather than
     *   hardcoded because the caller is the one that knows whether it is
     *   competing with playback for cores.
     * @param profileFile when set, ORT writes a per-node profile here on
     *   [OrtSession.endProfiling] — the only authoritative way to learn which
     *   provider actually received which node.
     */
    fun create(
        context: Context,
        accelerator: StemsAccelerator,
        threads: Int,
        profileFile: File? = null,
    ): OrtSession? {
        val model = runCatching { modelFile(context) }.getOrNull() ?: return null

        val options = OrtSession.SessionOptions().apply {
            runCatching { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT) }
            if (threads > 0) runCatching { setIntraOpNumThreads(threads) }
            // The arena retains every block it allocates for the session's life
            // and memory-pattern optimisation pre-plans allocations the length
            // of the longest input; neither is worth paying for a model whose
            // input is a fixed 22.8-second window.
            runCatching { setCPUArenaAllocator(false) }
            runCatching { setMemoryPatternOptimization(false) }
            profileFile?.let { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    enableProfiling(file.absolutePath)
                }
            }
            register(accelerator, context)
        }

        return runCatching {
            OrtEnvironment.getEnvironment().createSession(model.absolutePath, options)
        }.onFailure {
            Log.w(TAG, "Session creation failed on $accelerator", it)
            runCatching { options.close() }
        }.getOrNull()
    }

    /** Registers [accelerator]'s execution provider onto [this]. Throws on failure. */
    private fun OrtSession.SessionOptions.register(
        accelerator: StemsAccelerator,
        context: Context,
    ) {
        when (accelerator) {
            StemsAccelerator.AUTO -> Unit

            // The boolean is "use XNNPACK"; XNNPACK kernels are compiled into
            // this build but have no dedicated append call, they ride on the
            // CPU provider's flag.
            StemsAccelerator.CPU_XNNPACK -> addCPU(true)

            StemsAccelerator.CPU -> addCPU(false)

            // CPU_DISABLED is the point here: without it NNAPI hands any
            // operator it has no accelerator kernel for back to its own
            // reference CPU implementation, which is typically slower than
            // ORT's. Leaving it out of the graph is better than letting it run
            // badly.
            //
            // No device-selection flag exists, so which of NNAPI's devices wins
            // is the vendor driver's decision — see StemsAccelerator's docs.
            StemsAccelerator.ACCELERATOR ->
                addNnapi(EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))

            StemsAccelerator.SNAPDRAGON_NPU -> {
                // Provider order matters: the plugin first so its nodes are
                // claimed before NNAPI sees them, NNAPI second for whatever it
                // can still take, and finally plain CPU — addCPU last is what
                // makes an unclaimed node runnable at all rather than leaving a
                // gap ORT cannot compile across.
                registerQualcommPlugin(context).getOrThrow()
                addNnapi(EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
                addCPU(false)
            }
        }
    }

    private const val PLUGIN_DIR = "stems"
}
