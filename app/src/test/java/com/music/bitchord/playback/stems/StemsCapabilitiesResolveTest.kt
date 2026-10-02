package com.music.bitchord.playback.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolution rules for the vocal-separation backend choice.
 *
 * The behaviour worth pinning down here is not the happy path, which is one
 * line, but the three ways a stored preference can turn out to be unsatisfiable:
 * a rung the device never had, a rung it has but that was handed no work, and a
 * rung that is merely unknown. Each has a different correct answer, and getting
 * them confused is how a settings choice silently stops doing anything.
 */
class StemsCapabilitiesResolveTest {

    private fun result(
        accelerator: StemsAccelerator,
        state: StemsBackendState,
        ops: Int = 0,
        total: Int = 0,
        reason: String = "test",
    ) = StemsBackendResult(accelerator, state, reason, ops, total)

    /** A device with nothing but portable CPU kernels. */
    private val cpuOnly = StemsCapabilities(
        backends = listOf(
            result(StemsAccelerator.CPU, StemsBackendState.UNVERIFIED),
        ),
        effective = StemsAccelerator.CPU,
    )

    /** A Snapdragon with a plugin that a probe found and confirmed was used. */
    private val snapdragonWorking = StemsCapabilities(
        backends = listOf(
            result(StemsAccelerator.CPU, StemsBackendState.UNVERIFIED),
            result(StemsAccelerator.SNAPDRAGON_NPU, StemsBackendState.VERIFIED, ops = 80, total = 120),
        ),
        effective = StemsAccelerator.SNAPDRAGON_NPU,
    )

    /** The case this feature mostly runs in: the NPU loaded and did nothing. */
    private val snapdragonUseless = StemsCapabilities(
        backends = listOf(
            result(StemsAccelerator.CPU, StemsBackendState.UNVERIFIED),
            result(
                StemsAccelerator.SNAPDRAGON_NPU,
                StemsBackendState.VERIFIED,
                reason = "Ran in 812ms · 0 of 114 nodes offloaded",
            ),
        ),
        effective = StemsAccelerator.CPU,
    )

    @Test
    fun `an explicit working choice is honoured`() {
        assertEquals(
            StemsAccelerator.SNAPDRAGON_NPU,
            snapdragonWorking.resolve(StemsAccelerator.SNAPDRAGON_NPU),
        )
    }

    @Test
    fun `a rung that took no nodes is not honoured`() {
        // Verified but useless: keeping it would add a session and a graph
        // partition for no offloaded work, and on a model this shape that is
        // slower than not registering the provider at all.
        assertEquals(
            StemsAccelerator.CPU,
            snapdragonUseless.resolve(StemsAccelerator.SNAPDRAGON_NPU),
        )
    }

    @Test
    fun `an unavailable rung falls back to whatever the device prefers`() {
        val device = StemsCapabilities(
            backends = listOf(
                result(StemsAccelerator.CPU, StemsBackendState.UNVERIFIED),
                result(StemsAccelerator.SNAPDRAGON_NPU, StemsBackendState.UNAVAILABLE, reason = "no plugin"),
            ),
            effective = StemsAccelerator.CPU_XNNPACK,
        )
        assertEquals(
            StemsAccelerator.CPU_XNNPACK,
            device.resolve(StemsAccelerator.SNAPDRAGON_NPU),
        )
    }

    @Test
    fun `automatic resolves to the device's own preference`() {
        assertEquals(
            StemsAccelerator.SNAPDRAGON_NPU,
            snapdragonWorking.resolve(StemsAccelerator.AUTO),
        )
    }

    @Test
    fun `resolution never returns null even with nothing usable`() {
        // Every backend unreachable is a state the vocaller has to survive:
        // it produces no mask, and playback carries on untouched.
        val broken = StemsCapabilities(
            backends = listOf(result(StemsAccelerator.CPU, StemsBackendState.UNAVAILABLE)),
            effective = null,
        )
        assertEquals(StemsAccelerator.CPU, broken.resolve(StemsAccelerator.AUTO))
        assertEquals(StemsAccelerator.CPU, broken.resolve(StemsAccelerator.ACCELERATOR))
        assertNull(broken.effective)
    }

    @Test
    fun `coverage is only reported once measured`() {
        val unmeasured = result(StemsAccelerator.CPU, StemsBackendState.UNVERIFIED)
        assertNull(unmeasured.coverage)

        val measured = result(StemsAccelerator.CPU, StemsBackendState.VERIFIED, ops = 25, total = 100)
        assertEquals(0.25f, measured.coverage!!, 1e-6f)
        assertTrue(measured.effective)

        val noNodes = result(StemsAccelerator.CPU, StemsBackendState.VERIFIED, ops = 0, total = 114)
        assertEquals(0.0f, noNodes.coverage!!, 1e-6f)
        assertFalse(noNodes.effective)
    }

    @Test
    fun `every accelerator has a label and a detail`() {
        // These render directly into the picker, so an empty string would be a
        // blank row rather than an obvious failure.
        StemsAccelerator.entries.forEach {
            assertTrue("no label for $it", it.label.isNotBlank())
            assertTrue("no detail for $it", it.detail.isNotBlank())
        }
    }
}
