package it.gangitano.karooridereplay.extension

import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import io.hammerhead.karooext.models.DataType
import it.gangitano.karooridereplay.replay.FitRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Arming on play and disarming on exit, the two calls the UI makes around a replay.
 *
 * Only the arming itself is asserted, not the fix it publishes: Robolectric has no shadow
 * for `LocationManager.addTestProvider` / `setTestProviderLocation`, so those calls do
 * nothing observable and the test provider's location can never be read back. What still
 * matters here is that neither call throws and neither disturbs the sensor stream, since
 * both run on the UI's play and back paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockLocationArmingTest {
    @get:Rule val karoo = FakeKarooRule()

    @Test
    fun `arming and disarming mid-replay leaves the sensor stream flowing`() {
        val host = karoo.host<KarooRideReplayExtension>()
        val extension = karoo.awaitValue { KarooRideReplayExtension.instance }
        extension.replayEngine.load(
            listOf(
                FitRecord(timestampMs = 0, lat = 60.19, lng = 24.94, power = 200),
                FitRecord(timestampMs = 1_000, lat = 60.20, lng = 24.95, power = 210),
                FitRecord(timestampMs = 2_000, lat = 60.21, lng = 24.96, power = 220),
                FitRecord(timestampMs = 3_000, lat = 60.22, lng = 24.97, power = 230),
            ),
        )
        extension.replayEngine.setSpeed(10.0)
        val power = host.connectDevice("replay-power")
        power.awaitConnected()

        extension.armMockLocation()
        extension.armMockLocation() // arming twice is a no-op, not a crash
        extension.replayEngine.play()
        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 220.0 }
        extension.disarmMockLocation()
        extension.disarmMockLocation()
        power.awaitDataPoint(DataType.Source.POWER) { it.singleValue == 230.0 }
    }
}
