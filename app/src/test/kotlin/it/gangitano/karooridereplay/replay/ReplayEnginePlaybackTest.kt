package it.gangitano.karooridereplay.replay

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playback control and the edges of the timeline: what play, pause, seek and load do to
 * the position, and how a ride's markers survive (or don't) a reload.
 *
 * Waits read the engine's own flows with a timeout instead of sleeping, so a stalled
 * playback fails the test rather than hanging it.
 */
class ReplayEnginePlaybackTest {

    private val engine = ReplayEngine()

    @After fun tearDown() = engine.destroy()

    /** One second per sample, powered by the values given. */
    private fun ride(vararg power: Int): List<FitRecord> =
        power.mapIndexed { index, watts ->
            FitRecord(timestampMs = START_MS + index * 1_000L, power = watts)
        }

    private fun awaitPower(watts: Int, timeoutMs: Long = 10_000) = runBlocking {
        withTimeout(timeoutMs) { engine.currentRecord.first { it?.power == watts } }
    }

    /** True when [watts] never reaches the engine within [ms]; the negative of [awaitPower]. */
    private fun neverArrives(watts: Int, ms: Long) = runBlocking {
        withTimeoutOrNull(ms) { engine.currentRecord.first { it?.power == watts } } == null
    }

    @Test
    fun `playing with no ride loaded stays idle`() {
        engine.play()

        assertEquals(ReplayEngine.State.IDLE, engine.state.value)
        assertNull(engine.currentRecord.value)
        assertEquals(0L, engine.totalSeconds)
    }

    @Test
    fun `a one-sample ride finishes the moment it plays`() {
        engine.load(ride(200))

        engine.play()

        assertEquals(ReplayEngine.State.FINISHED, runBlocking {
            withTimeout(10_000) { engine.state.first { it == ReplayEngine.State.FINISHED } }
        })
        assertEquals(0L, engine.elapsedSeconds.value)
    }

    @Test
    fun `pause holds the position and play resumes from it`() {
        engine.load(
            listOf(
                FitRecord(timestampMs = 0, power = 200),
                FitRecord(timestampMs = 1_000, power = 210),
                FitRecord(timestampMs = 60_000, power = 220),
            ),
        )
        engine.play()
        awaitPower(210)

        engine.pause()

        assertEquals(ReplayEngine.State.PAUSED, engine.state.value)
        assertEquals(1L, engine.elapsedSeconds.value)
        assertEquals(210, engine.currentRecord.value?.power)

        engine.setSpeed(100.0) // the 59 s gap left in the ride now costs 590 ms
        engine.play()
        awaitPower(220)
        assertEquals(60L, engine.elapsedSeconds.value)
    }

    @Test
    fun `the playback speed is clamped to the supported range`() {
        engine.setSpeed(0.0)
        assertEquals(0.1, engine.playbackSpeed.value, 0.0)
        engine.setSpeed(1_000.0)
        assertEquals(100.0, engine.playbackSpeed.value, 0.0)
        engine.setSpeed(2.5)
        assertEquals(2.5, engine.playbackSpeed.value, 0.0)
    }

    @Test
    fun `a marker already dropped at this position is not added twice`() {
        engine.load(ride(200, 210, 220))

        engine.seek(1)
        engine.addMarker()
        engine.addMarker()

        assertEquals(listOf(1L), engine.markers.value)
    }

    @Test
    fun `loop arms only once two markers exist`() {
        engine.load(ride(200, 210, 220))

        engine.seek(0); engine.addMarker()
        engine.toggleLoop()
        assertFalse(engine.loop.value)

        engine.seek(2); engine.addMarker()
        engine.toggleLoop()
        assertTrue(engine.loop.value)
        engine.toggleLoop()
        assertFalse(engine.loop.value)
    }

    @Test
    fun `clearing markers turns loop off`() {
        engine.load(ride(200, 210, 220))
        engine.seek(0); engine.addMarker()
        engine.seek(2); engine.addMarker()
        engine.toggleLoop()
        assertTrue(engine.loop.value)

        engine.clearMarkers()

        assertEquals(emptyList<Long>(), engine.markers.value)
        assertFalse(engine.loop.value)
    }

    @Test
    fun `loading a new ride clears its way back to the first sample`() {
        engine.load(ride(200, 210, 220))
        engine.seek(1); engine.addMarker()
        engine.seek(2); engine.addMarker()
        engine.toggleLoop()

        engine.load(ride(300, 310))

        assertEquals(emptyList<Long>(), engine.markers.value)
        assertFalse(engine.loop.value)
        assertEquals(0L, engine.elapsedSeconds.value)
        assertEquals(300, engine.currentRecord.value?.power)
        assertEquals(1L, engine.totalSeconds)
    }

    @Test
    fun `seek clamps to the ride's range`() {
        engine.load(ride(200, 210, 220))

        engine.seek(-30)
        assertEquals(0L, engine.elapsedSeconds.value)

        engine.seek(500)
        assertEquals(2L, engine.elapsedSeconds.value)
        assertEquals(220, engine.currentRecord.value?.power)
    }

    @Test
    fun `seek on an empty ride changes nothing`() {
        engine.load(emptyList())

        engine.seek(5)

        assertNull(engine.currentRecord.value)
        assertEquals(0L, engine.elapsedSeconds.value)
        assertEquals(0L, engine.totalSeconds)
    }

    @Test
    fun `loading a new ride stops the one in flight`() {
        engine.load(ride(200, 210))
        engine.play()
        awaitPower(200)

        engine.load(ride(300, 310))

        assertEquals(ReplayEngine.State.IDLE, engine.state.value)
        assertEquals(300, engine.currentRecord.value?.power)
        assertTrue("the replaced ride's second sample never lands", neverArrives(210, 1_500))
    }

    @Test
    fun `destroy stops the playback`() {
        engine.load(ride(200, 210))
        engine.play()

        engine.destroy()

        assertTrue("no sample lands after destroy", neverArrives(210, 1_500))
    }

    @Test
    fun `elapsed and total seconds are measured from the first sample`() {
        engine.load(
            listOf(
                FitRecord(timestampMs = START_MS, power = 200),
                FitRecord(timestampMs = START_MS + 30_000L, power = 210),
                FitRecord(timestampMs = START_MS + 60_000L, power = 220),
            ),
        )

        assertEquals(60L, engine.totalSeconds)
        engine.seek(30)
        assertEquals(30L, engine.elapsedSeconds.value)
        assertEquals(210, engine.currentRecord.value?.power)
    }

    private companion object {
        const val START_MS = 1_700_000_000_000L
    }
}
