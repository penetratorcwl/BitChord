package com.music.bitchord.playback.stems

/**
 * Where the vocal-separation model should run, as chosen in Settings.
 *
 * ## Why this is not "CPU / GPU / NPU"
 *
 * The obvious three-way split is not implementable on Android, and an enum that
 * offers it would be lying to the user on every device.
 *
 * ONNX Runtime's Android build ships exactly two execution providers that can
 * be registered from the Java API: `CPU` and `NNAPI`. There is no GPU provider
 * — the CUDA/DirectML/TensorRT ones are for other platforms — so a phone GPU is
 * reachable *only* through NNAPI. And NNAPI exposes no way to choose which of
 * its backing devices to use: its flags are `USE_FP16`, `USE_NCHW`,
 * `CPU_DISABLED` and `CPU_ONLY`, none of which selects a GPU over an NPU. NNAPI
 * picks, using the vendor driver, and the app is told only which node ended up
 * where by reading the run's profile.
 *
 * So "GPU" and "NPU" are the same request as far as this app is concerned, and
 * the only genuinely separate NPU path is Qualcomm's plugin execution provider,
 * which is a separate `.so` that has to be present on the device. Hence four
 * rungs: two real CPU ones, one honest accelerator one, and one Snapdragon
 * special case.
 *
 * ## What each rung actually is
 *
 * [CPU] is ONNX Runtime's own kernels with no acceleration layer: the thing
 * that always works.
 *
 * [CPU_XNNPACK] is still CPU silicon, routed through XNNPACK's hand-written
 * f32 kernels. A large win on graphs of convolutions and matrix multiplies,
 * because ORT's generic CPU kernels are far more portable and far slower.
 *
 * [ACCELERATOR] is NNAPI, which reaches whichever dedicated hardware the vendor
 * driver offers — an NPU on phones that have one, a GPU otherwise.
 *
 * [SNAPDRAGON_NPU] is Qualcomm's plugin provider, which targets the Hexagon
 * tensor accelerator explicitly and so is the only rung that is really an NPU
 * rather than "whatever NNAPI felt like".
 *
 * [AUTO] defers to [StemsBackendProbe.staticProbe]'s ordering.
 *
 * ## The caveat that belongs next to this enum
 *
 * None of the above helps the model this app ships. `vocals_umxhq_int8.onnx`
 * is three bidirectional LSTM layers between two batch-norms, with no
 * convolution anywhere in its graph — and neither NNAPI nor XNNPACK implements
 * `LSTM`, `DynamicQuantizeLSTM` or `MatMulInteger`. There is consequently
 * nothing for an accelerator to take, and [StemsBackendProbe] reports that as
 * zero node coverage rather than pretending otherwise. The rungs are here
 * because the setting is about the *engine*, and the engine outlives the model:
 * see [StemsBackendProbe] for what changing the model would unlock.
 */
enum class StemsAccelerator(
    val label: String,
    val detail: String,
) {
    AUTO(
        "Automatic",
        "Fastest backend this device actually delivers",
    ),

    /** ONNX Runtime's own CPU kernels. Always available, never the fastest. */
    CPU(
        "CPU",
        "Portable ONNX Runtime kernels · always available",
    ),

    /** XNNPACK's hand-written CPU kernels — same silicon, much better code. */
    CPU_XNNPACK(
        "CPU (optimised)",
        "XNNPACK f32 kernels · fast for convolutions and matrix multiplies",
    ),

    /** NNAPI, reaching the vendor's chosen accelerator: NPU where one exists, GPU otherwise. */
    ACCELERATOR(
        "GPU / NPU",
        "NNAPI · dedicated accelerator where the driver offers one",
    ),

    /** Qualcomm's plugin execution provider, targeting Hexagon explicitly. */
    SNAPDRAGON_NPU(
        "NPU (Snapdragon)",
        "Qualcomm plugin provider · Hexagon tensor accelerator",
    ),
}

/**
 * Whether one [StemsAccelerator] can run here, and — when it cannot — what
 * stopped it.
 *
 * Three states, not two: a rung can be *impossible* (this phone has no NNAPI,
 * or no Qualcomm plugin), *reachable but unproven* (the provider is registered
 * but nothing has run on it yet), or *working* (a real inference completed and
 * its profile was read). Collapsing the last two into "available" is how a
 * settings screen ends up promising accelerator support that quietly never
 * engages.
 */
enum class StemsBackendState {
    /** No path to this backend exists on this device. [StemsBackendResult.reason] says why. */
    UNAVAILABLE,

    /** The provider is present and registered; whether it wins any work is not yet known. */
    UNVERIFIED,

    /** A real inference ran on it and its profile was read. */
    VERIFIED,
    ;

    val usable: Boolean get() = this != UNAVAILABLE
}

/**
 * One rung's probe result.
 *
 * @param accelerator the rung this describes.
 * @param state how far this rung has been established.
 * @param reason always populated, and human-readable: what makes the rung
 *   available, what blocks it, or what was measured about it.
 * @param supportedOps nodes actually assigned to this backend, when [state] is
 *   [StemsBackendState.VERIFIED].
 * @param totalOps nodes in the model, when [state] is [StemsBackendState.VERIFIED].
 * @param millis wall time of the measured inference, when one was run.
 */
data class StemsBackendResult(
    val accelerator: StemsAccelerator,
    val state: StemsBackendState,
    val reason: String,
    val supportedOps: Int = 0,
    val totalOps: Int = 0,
    val millis: Long = 0,
) {
    /**
     * Share of the graph this backend actually took, or null when unmeasured.
     *
     * A backend that received zero nodes is the case worth surfacing hardest:
     * it loaded, it is listed, and it did no work at all. Reading
     * [supportedOps] as a percentage of [totalOps] is the only way to tell that
     * apart from "fast but useless".
     */
    val coverage: Float?
        get() = if (state == StemsBackendState.VERIFIED && totalOps > 0) {
            supportedOps.toFloat() / totalOps.toFloat()
        } else {
            null
        }

    /**
     * Whether this rung is worth actually using.
     *
     * A verified rung that took no nodes is not: it would add a session and a
     * graph partition for nothing. Callers resolving [StemsAccelerator.AUTO]
     * skip these, and so should an explicit selection, which is why
     * [StemsCapabilities.resolve] treats them as unusable even though
     * [StemsBackendState.usable] is true.
     */
    val effective: Boolean
        get() = state == StemsBackendState.VERIFIED && supportedOps > 0
}

/**
 * The whole probe: every rung, plus which one will be used.
 *
 * @param backends per-rung results in [StemsAccelerator] declaration order.
 * @param effective the rung [StemsAccelerator.AUTO] resolves to, or null when
 *   nothing usable exists — a supported state, not a failure: separation then
 *   produces no mask and playback is untouched.
 */
data class StemsCapabilities(
    val backends: List<StemsBackendResult>,
    val effective: StemsAccelerator?,
) {
    private val byAccelerator: Map<StemsAccelerator, StemsBackendResult> =
        backends.associateBy { it.accelerator }

    fun probeFor(accelerator: StemsAccelerator): StemsBackendResult? = byAccelerator[accelerator]

    /**
     * The rung to use for a stored preference, after checking this device can
     * still deliver it.
     *
     * A preference the device cannot honour is resolved to [StemsAccelerator.AUTO]
     * rather than refused: the user asked for the fastest thing available, and
     * on a phone that changed under them the fastest thing available is now
     * something else. Saying so is [StemsBackendResult.reason]'s job, not this
     * function's — it returns a backend, not an explanation.
     */
    fun resolve(preference: StemsAccelerator): StemsAccelerator {
        if (preference != StemsAccelerator.AUTO) {
            val probe = byAccelerator[preference]
            if (probe != null && probe.effective) return preference
        }
        return effective ?: StemsAccelerator.CPU
    }
}
