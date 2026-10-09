package it.gangitano.karooridereplay.data

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import it.gangitano.karooridereplay.data.FitFileRepository.FitFileEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Which files the picker lists, from where, and in what order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FitFileRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FitFileRepository(context)
    private val root: File = Environment.getExternalStorageDirectory()
    private val fitFiles = File(root, "FitFiles")
    private val downloads = File(root, "Download")
    private val elsewhere = File(root, "Elsewhere")

    @Before
    fun setUp() {
        fitFiles.mkdirs()
        downloads.mkdirs()
        elsewhere.mkdirs()
    }

    @After
    fun tearDown() {
        listOf(fitFiles, downloads, elsewhere).forEach { it.deleteRecursively() }
    }

    private fun rides(folder: File): List<String> = repository.listAvailable().map { it.displayName }

    @Test
    fun `only FIT files big enough to be a ride are listed`() {
        write(fitFiles, "a.fit", RIDE_BYTES)
        write(fitFiles, "c.FIT", RIDE_BYTES)
        write(fitFiles, "notes.txt", RIDE_BYTES)
        write(fitFiles, "stub.fit", 1_000)

        assertEquals(listOf("a", "c"), rides(fitFiles).sorted())
    }

    @Test
    fun `the newest ride comes back first`() {
        write(fitFiles, "old.fit", RIDE_BYTES, modifiedMs = 1_000)
        write(fitFiles, "new.fit", RIDE_BYTES, modifiedMs = 9_000)

        assertEquals(listOf("new", "old"), rides(fitFiles))
    }

    @Test
    fun `the download folder and the app's own folder are scanned too`() {
        write(downloads, "dropped.fit", RIDE_BYTES)
        val inbox = requireNotNull(context.getExternalFilesDir(null))
        write(inbox, "sideloaded.fit", RIDE_BYTES)

        val listed = rides(fitFiles)

        assertTrue(listed.containsAll(listOf("dropped", "sideloaded")))
        inbox.deleteRecursively()
    }

    @Test
    fun `a FIT file outside the known folders is ignored`() {
        write(elsewhere, "x.fit", RIDE_BYTES)

        assertEquals(emptyList<String>(), rides(fitFiles))
    }

    @Test
    fun `the row labels show the file size`() {
        assertEquals("500 B", entry(500).sizeLabel)
        assertEquals("2.0 KB", entry(2_048).sizeLabel)
        assertEquals("5.0 MB", entry(5 * 1024 * 1024).sizeLabel)
    }

    private fun entry(sizeBytes: Long) =
        FitFileEntry(File(root, "x.fit"), "x", sizeBytes, modifiedMs = 0L)

    private fun write(folder: File, name: String, sizeBytes: Int, modifiedMs: Long = 0L): File {
        val file = File(folder, name)
        file.writeBytes(ByteArray(sizeBytes))
        file.setLastModified(modifiedMs)
        return file
    }

    private companion object {
        /** Above FitFileRepository's floor for a file with a real ride in it. */
        const val RIDE_BYTES = 20_000
    }
}
