package it.gangitano.karooridereplay.extension

import fi.nikosavola.karooext.testing.DeviceRecorder
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnConnectionStatus
import it.gangitano.karooridereplay.replay.FitRecord
import it.gangitano.karooridereplay.replay.Sensor
import it.gangitano.karooridereplay.vdevice.ReplayVirtualDevice.Companion.TYPE_SPD_DISTANCE_DIFF_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The real extension service runs against the fake Karoo; the test plays the ride app and the rider.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KarooRideReplayExtensionTest {
    @get:Rule val karoo = FakeKarooRule()

    private val host by lazy { karoo.host<KarooRideReplayExtension>() }

    private val engine
        get() = run {
            host
            karoo.awaitValue { KarooRideReplayExtension.instance }.replayEngine
        }

    private fun ride(vararg records: FitRecord) {
        engine.load(records.toList())
        engine.setSpeed(100.0)
    }

    private fun connect(dataTypeId: String): DeviceRecorder {
        val scan = host.startScan()
        scan.await(10_000, after = 3) { true }
        return host.connectDevice(scan.items.first { it.dataTypes.contains(dataTypeId) })
    }

    private fun DeviceRecorder.statuses() =
        items.filterIsInstance<OnConnectionStatus>().map { it.status }

    @Test
    fun `a scan offers one virtual device per sensor`() {
        val scan = host.startScan()

        scan.await(10_000, after = 3) { true }

        assertEquals(4, scan.items.size)
        assertEquals(4, scan.items.map { it.uid }.toSet().size)
        assertTrue(scan.items.all { it.extension == "ride-replay" })
        assertTrue(scan.items.any { it.dataTypes.contains(DataType.Source.POWER) })
        assertTrue(scan.items.any { it.dataTypes.contains(TYPE_SPD_DISTANCE_DIFF_ID) })
    }

    @Test
    fun `a connected power sensor follows the replayed ride`() {
        ride(
            FitRecord(timestampMs = 0, power = 200),
            FitRecord(timestampMs = 1_000, power = 210),
            FitRecord(timestampMs = 2_000, power = 220),
        )
        val events = connect(DataType.Source.POWER)

        events.awaitConnected()
        engine.play()

        events.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 220.0 }
        assertEquals(
            listOf(200.0, 210.0, 220.0),
            events.dataPoints(DataType.Source.POWER).map { it.singleValue },
        )
        assertEquals(listOf(ConnectionStatus.SEARCHING, ConnectionStatus.CONNECTED), events.statuses())
    }

    @Test
    fun `cycling a sensor drops it out and brings it back`() {
        ride(FitRecord(timestampMs = 0, heartRate = 120), FitRecord(timestampMs = 1_000, heartRate = 125))
        val events = connect(DataType.Source.HEART_RATE)
        events.awaitConnected()

        engine.cycleSensorState(Sensor.HEART_RATE)
        events.awaitStatus(ConnectionStatus.SEARCHING, after = 2)
        engine.cycleSensorState(Sensor.HEART_RATE)
        events.awaitStatus(ConnectionStatus.DISCONNECTED)
        val before = events.items.size
        engine.cycleSensorState(Sensor.HEART_RATE)

        events.awaitConnected(after = before)
        events.awaitDataPoint(DataType.Source.HEART_RATE, after = before)
    }

    @Test
    fun `the speed sensor also reports the distance covered between records`() {
        ride(
            FitRecord(timestampMs = 0, speed = 5.0, distance = 0.0),
            FitRecord(timestampMs = 1_000, speed = 5.0, distance = 10.0),
            FitRecord(timestampMs = 2_000, speed = 6.0, distance = 25.0),
        )
        val events = connect(DataType.Source.SPEED)
        events.awaitConnected()

        engine.play()

        events.awaitDataPoint(TYPE_SPD_DISTANCE_DIFF_ID) { it.singleValue == 15.0 }
        assertEquals(
            listOf(10.0, 15.0),
            events.dataPoints(TYPE_SPD_DISTANCE_DIFF_ID).map { it.singleValue },
        )
    }

    @Test
    fun `an unknown device uid is ignored and a disconnect stops the stream`() {
        assertThrows(IllegalStateException::class.java) {
            host.connectDevice("replay-all").await(1_200) { true }
        }

        ride(FitRecord(timestampMs = 0, power = 200), FitRecord(timestampMs = 1_000, power = 210))
        val events = connect(DataType.Source.POWER)
        events.awaitConnected()
        // Wait for the initial burst (battery status and the parked sample) to land, so
        // nothing is still in flight when the mark below is taken.
        events.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 200.0 }
        host.disconnectDevice(events)
        val before = events.items.size

        engine.play()

        assertThrows(IllegalStateException::class.java) { events.await(500, after = before) { true } }
    }
}
