package it.gangitano.karooridereplay.extension

import fi.nikosavola.karooext.testing.DeviceRecorder
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnDataPoint
import it.gangitano.karooridereplay.replay.FitRecord
import it.gangitano.karooridereplay.replay.ReplayEngine
import it.gangitano.karooridereplay.replay.Sensor
import it.gangitano.karooridereplay.vdevice.ReplayVirtualDevice.Companion.TYPE_SPD_DISTANCE_DIFF_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the four virtual devices publish while a ride drives them, observed through
 * the real extension service and the fake Karoo. The pairing-level tests live in
 * [it.gangitano.karooridereplay.vdevice.ReplayVirtualDeviceTest]; these are about the
 * wiring: which field each pair carries, what a dropout does to a neighbour, and how
 * seeking and looping reach a paired sensor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplaySensorStreamsTest {
    @get:Rule val karoo = FakeKarooRule()

    private val host by lazy { karoo.host<KarooRideReplayExtension>() }

    private val engine
        get() = run {
            host
            karoo.awaitValue { KarooRideReplayExtension.instance }.replayEngine
        }

    /** Load a ride and play it 100x, so a recorded second costs ten milliseconds. */
    private fun ride(vararg records: FitRecord) {
        engine.load(records.toList())
        engine.setSpeed(100.0)
    }

    private fun connected(uid: String): DeviceRecorder =
        host.connectDevice(uid).also { it.awaitConnected() }

    private fun DeviceRecorder.valuesOf(dataTypeId: String): List<Double> =
        dataPoints(dataTypeId).mapNotNull { it.singleValue }

    @Test
    fun `each pair carries only its own sensor's field`() {
        ride(
            FitRecord(
                timestampMs = 0, power = 200, heartRate = 120, cadence = 90, speed = 5.0, distance = 0.0,
            ),
        )
        val power = connected("replay-power")
        val hr = connected("replay-hr")
        val cadence = connected("replay-cadence")
        val speed = connected("replay-speed")

        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 200.0 }
        hr.awaitDataPoint(DataType.Source.HEART_RATE) { it.singleValue == 120.0 }
        cadence.awaitDataPoint(DataType.Source.CADENCE) { it.singleValue == 90.0 }
        speed.awaitDataPoint(DataType.Source.SPEED) { it.singleValue == 5.0 }

        assertEquals(listOf(200.0), power.valuesOf(DataType.Source.POWER))
        assertEquals(listOf(120.0), hr.valuesOf(DataType.Source.HEART_RATE))
        assertEquals(listOf(90.0), cadence.valuesOf(DataType.Source.CADENCE))
        assertEquals(listOf(5.0), speed.valuesOf(DataType.Source.SPEED))
        assertEquals(
            listOf(DataType.Field.POWER),
            power.items.filterIsInstance<OnDataPoint>().flatMap { it.dataPoint.values.keys }.distinct(),
        )
        assertEquals(
            "replay-power",
            power.items.filterIsInstance<OnDataPoint>().first().dataPoint.sourceId,
        )
    }

    @Test
    fun `a sensor the ride never records stays connected and silent`() {
        ride(FitRecord(timestampMs = 0, power = 200), FitRecord(timestampMs = 1_000, power = 210))
        val hr = connected("replay-hr")
        val power = connected("replay-power")

        engine.play()

        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 210.0 }
        assertEquals(emptyList<Double>(), hr.valuesOf(DataType.Source.HEART_RATE))
        assertEquals(ConnectionStatus.CONNECTED, hr.connectionStatus)
    }

    @Test
    fun `pairing mid-ride starts from the sample the engine is parked on`() {
        ride(
            FitRecord(timestampMs = 0, power = 200),
            FitRecord(timestampMs = 1_000, power = 210),
            FitRecord(timestampMs = 2_000, power = 220),
            FitRecord(timestampMs = 3_000, power = 230),
        )
        engine.seek(2)

        val power = connected("replay-power")
        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 220.0 }
        engine.play()
        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 230.0 }

        assertEquals(listOf(220.0, 230.0), power.valuesOf(DataType.Source.POWER))
    }

    @Test
    fun `scrubbing back re-publishes the earlier sample to the paired sensor`() {
        ride(
            FitRecord(timestampMs = 0, power = 200),
            FitRecord(timestampMs = 1_000, power = 210),
            FitRecord(timestampMs = 2_000, power = 220),
        )
        val power = connected("replay-power")

        engine.play()
        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 220.0 }
        val mark = power.mark()
        engine.seek(0)

        power.awaitDataPoint(DataType.Source.POWER, after = mark) { it.singleValue == 200.0 }
        assertEquals(listOf(200.0, 210.0, 220.0, 200.0), power.valuesOf(DataType.Source.POWER))
    }

    @Test
    fun `a dropped sensor reports disconnected while its neighbour keeps streaming`() {
        ride(
            FitRecord(timestampMs = 0, power = 200, heartRate = 120),
            FitRecord(timestampMs = 1_000, power = 210, heartRate = 125),
        )
        val hr = connected("replay-hr")
        val power = connected("replay-power")

        engine.cycleSensorState(Sensor.HEART_RATE) // searching
        engine.cycleSensorState(Sensor.HEART_RATE) // missing
        hr.awaitStatus(ConnectionStatus.DISCONNECTED)
        val mark = hr.mark()

        engine.play()

        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 210.0 }
        assertEquals(listOf(120.0), hr.valuesOf(DataType.Source.HEART_RATE))
        assertEquals(mark, hr.items.size)
        // The ride's second reading never reaches the dropped sensor.
        assertThrows(IllegalStateException::class.java) { hr.await(500, after = mark) { true } }
    }

    @Test
    fun `two pairings of one device both follow the ride`() {
        ride(FitRecord(timestampMs = 0, power = 200), FitRecord(timestampMs = 1_000, power = 210))
        val first = connected("replay-power")
        val second = connected("replay-power")

        engine.play()

        first.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 210.0 }
        second.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 210.0 }
    }

    @Test
    fun `disconnecting one pairing leaves the other streaming`() {
        ride(
            FitRecord(timestampMs = 0, power = 200, heartRate = 120),
            FitRecord(timestampMs = 1_000, power = 210, heartRate = 125),
        )
        val power = connected("replay-power")
        val hr = connected("replay-hr")
        // Let the initial burst land first: an emission still in flight when the mark is
        // taken would otherwise arrive after the disconnect and look like a live stream.
        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 200.0 }
        host.disconnectDevice(power)
        val mark = power.mark()

        engine.play()

        hr.awaitDataPoint(DataType.Source.HEART_RATE) { it.singleValue == 125.0 }
        assertThrows(IllegalStateException::class.java) { power.await(500, after = mark) { true } }
    }

    @Test
    fun `the speed pair integrates speed over the gap when the FIT has no distance`() {
        ride(FitRecord(timestampMs = 0, speed = 3.0), FitRecord(timestampMs = 2_000, speed = 3.0))
        val speed = connected("replay-speed")

        engine.play()

        speed.awaitDataPoint(TYPE_SPD_DISTANCE_DIFF_ID) { it.singleValue == 6.0 }
        assertEquals(listOf(6.0), speed.valuesOf(TYPE_SPD_DISTANCE_DIFF_ID))
    }

    @Test
    fun `a FIT distance that steps backwards sends no delta and still moves the baseline`() {
        ride(
            FitRecord(timestampMs = 0, speed = 5.0, distance = 100.0),
            FitRecord(timestampMs = 1_000, speed = 5.0, distance = 90.0),
            FitRecord(timestampMs = 2_000, speed = 5.0, distance = 120.0),
        )
        val speed = connected("replay-speed")

        engine.play()

        speed.awaitDataPoint(TYPE_SPD_DISTANCE_DIFF_ID) { it.singleValue == 30.0 }
        assertEquals(listOf(30.0), speed.valuesOf(TYPE_SPD_DISTANCE_DIFF_ID))
    }

    @Test
    fun `a loop between markers keeps the sensor fed and the ride from finishing`() {
        ride(
            FitRecord(timestampMs = 0, power = 200),
            FitRecord(timestampMs = 1_000, power = 210),
            FitRecord(timestampMs = 2_000, power = 220),
        )
        engine.seek(0); engine.addMarker()
        engine.seek(2); engine.addMarker()
        engine.toggleLoop()
        val power = connected("replay-power")
        val mark = power.mark()

        engine.play()

        // Playback restarts at the lower marker, then wraps there again on every lap.
        // The value emitted just before a wrap can be conflated away by the engine's
        // StateFlow, so the count of samples is what the loop is asserted on, not each lap.
        power.awaitDataPoint(DataType.Source.POWER, after = mark) { it.singleValue == 200.0 }
        repeat(5) {
            power.awaitDataPoint(DataType.Source.POWER, after = power.mark()) { true }
        }
        assertEquals(ReplayEngine.State.PLAYING, engine.state.value)
    }
}
