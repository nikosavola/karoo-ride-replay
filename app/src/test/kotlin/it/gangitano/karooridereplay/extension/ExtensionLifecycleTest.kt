package it.gangitano.karooridereplay.extension

import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.DataType
import it.gangitano.karooridereplay.replay.FitRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The service lifecycle the Karoo drives: a scan the rider backs out of, a restart
 * mid-replay, and what the process leaves behind once the service is destroyed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtensionLifecycleTest {
    @get:Rule val karoo = FakeKarooRule()

    @Test
    fun `a scan that is stopped announces nothing`() {
        val host = karoo.host<KarooRideReplayExtension>()

        val scan = host.startScan()
        host.stopScan(scan)

        // The announce delay is 500 ms; a cancelled job never reaches it.
        assertThrows(IllegalStateException::class.java) { scan.await(1_200) { true } }
        assertEquals(emptyList<Any>(), scan.items)
    }

    @Test
    fun `a service restart drops the loaded ride and the new service streams again`() {
        karoo.host<KarooRideReplayExtension>()
        val engine = karoo.awaitValue { KarooRideReplayExtension.instance }.replayEngine
        engine.load(listOf(FitRecord(timestampMs = 0, power = 200), FitRecord(timestampMs = 1_000, power = 210)))
        assertEquals(1L, engine.totalSeconds)

        val restarted = karoo.restart<KarooRideReplayExtension>()
        val revived = karoo.awaitValue {
            KarooRideReplayExtension.instance?.takeIf { it.replayEngine !== engine }
        }

        // Replay state lives in the service, so a fresh instance starts with no ride.
        assertEquals(0L, revived.replayEngine.totalSeconds)
        assertNull(revived.replayEngine.currentRecord.value)

        revived.replayEngine.load(
            listOf(FitRecord(timestampMs = 0, power = 300), FitRecord(timestampMs = 1_000, power = 310)),
        )
        revived.replayEngine.setSpeed(100.0)
        val power = restarted.connectDevice("replay-power")
        power.awaitConnected()
        revived.replayEngine.play()

        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 310.0 }
    }

    @Test
    fun `destroying the service clears the running instance`() {
        karoo.host<KarooRideReplayExtension>()
        karoo.awaitValue { KarooRideReplayExtension.instance }

        karoo.close()

        assertNull(KarooRideReplayExtension.instance)
    }
}
