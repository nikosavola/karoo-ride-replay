package it.gangitano.karooridereplay.ui

import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import it.gangitano.karooridereplay.data.FitFileRepository.FitFileEntry
import it.gangitano.karooridereplay.extension.KarooRideReplayExtension
import it.gangitano.karooridereplay.replay.FitRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The UI's view of the engine: what the picker remembers across view models, and how a
 * ride whose bytes are not a FIT file is reported and cached.
 *
 * The engine lives in the extension service, so the cases that need a loaded ride host
 * the real service through the fake Karoo first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayViewModelTest {
    @get:Rule val karoo = FakeKarooRule()

    /** A file that exists, so the picker offers it, but whose bytes are not a FIT file. */
    private fun broken(name: String) = File(karoo.app.cacheDir, name).apply { writeBytes(ByteArray(4096)) }

    private fun viewModel() = ReplayViewModel(karoo.app)

    private fun entry(file: File) =
        FitFileEntry(file = file, displayName = file.nameWithoutExtension, sizeBytes = 4096, modifiedMs = 0)

    /** Load a minute-long ride from [source] into the extension's engine, the way the picker would. */
    private fun engineRide(source: File) {
        karoo.host<KarooRideReplayExtension>()
        karoo.awaitValue { KarooRideReplayExtension.instance }.replayEngine.load(
            listOf(FitRecord(timestampMs = 0, power = 200), FitRecord(timestampMs = 60_000, power = 210)),
            source.absolutePath,
        )
    }

    @Test
    fun `starring a ride survives a new view model`() {
        val vm = viewModel()
        val ride = broken("starred.fit")
        val entry = entry(ride)

        vm.toggleStar(entry)

        assertTrue(vm.starred.value.contains(ride.absolutePath))
        val reopened = viewModel()
        assertTrue(reopened.starred.value.contains(ride.absolutePath))

        reopened.toggleStar(entry)

        assertFalse(reopened.starred.value.contains(ride.absolutePath))
    }

    @Test
    fun `a ride whose bytes are not a FIT is cached as unparseable`() {
        val vm = viewModel()
        val ride = broken("summary.fit")

        vm.ensureSummary(entry(ride))

        val summary = karoo.awaitValue { vm.summaries.value[ride.absolutePath] }
        assertEquals(null, summary.durationSeconds)
        assertEquals(null, summary.distanceMeters)
    }

    @Test
    fun `selecting a ride whose bytes are not a FIT reports an error`() {
        val vm = viewModel()
        val ride = broken("unreadable.fit")

        vm.selectRide(entry(ride))

        assertEquals(ReplayViewModel.LoadStatus.Loading, vm.loadStatus.value)
        assertNotNull(karoo.awaitValue { vm.loadStatus.value as? ReplayViewModel.LoadStatus.Error })
    }

    @Test
    fun `re-selecting the loaded ride reopens it in place instead of re-parsing`() {
        val ride = broken("reopened.fit")
        engineRide(ride)
        val vm = viewModel()
        val entry = entry(ride)

        vm.selectRide(entry) // the second tap on the row the engine already holds

        assertEquals(
            ReplayViewModel.LoadStatus.Loaded(totalSeconds = 60L, ridePath = ride.absolutePath),
            vm.loadStatus.value,
        )
        assertTrue(vm.hasActiveRide())
    }

    @Test
    fun `a new view model reconnects to the ride the engine still holds`() {
        val ride = broken("restored.fit")
        engineRide(ride)

        val reopened = viewModel() // as when the app is reopened from the Extensions list

        assertEquals(
            ReplayViewModel.LoadStatus.Loaded(totalSeconds = 60L, ridePath = ride.absolutePath),
            reopened.loadStatus.value,
        )
    }

    @Test
    fun `a ride loaded outside the picker is announced and shown as loaded`() {
        val vm = viewModel()
        val ride = broken("adb.fit")
        val announced = AtomicReference<String?>(null)
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.externalLoads.collect { announced.set(it) }
        }

        engineRide(ride)

        assertEquals(
            ReplayViewModel.LoadStatus.Loaded(totalSeconds = 60L, ridePath = ride.absolutePath),
            karoo.awaitValue { vm.loadStatus.value as? ReplayViewModel.LoadStatus.Loaded },
        )
        assertEquals(ride.absolutePath, karoo.awaitValue { announced.get() })
        job.cancel()
    }
}
