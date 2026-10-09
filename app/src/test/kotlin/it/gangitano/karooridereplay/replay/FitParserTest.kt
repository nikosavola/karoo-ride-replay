package it.gangitano.karooridereplay.replay

import com.garmin.fit.DateTime
import com.garmin.fit.File
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File as IoFile
import java.util.Date

/**
 * The FIT reader, driven by files this test writes with Garmin's own encoder. Every
 * value is whole in its stored unit, so a scale or offset mishandled by either side
 * still shows up as a mismatched number.
 */
class FitParserTest {

    @get:Rule val temp = TemporaryFolder()

    private val parser = FitParser()

    @Test
    fun `a record comes back with the fields and units the engine expects`() {
        val file = writeFit(
            RecordMesg().apply {
                timestamp = DateTime(Date(BASE_MS))
                positionLat = semicircles(60.19)
                positionLong = semicircles(24.94)
                altitude = 12.0f
                speed = 5.0f
                power = 210
                heartRate = 140.toShort()
                cadence = 88.toShort()
                distance = 1234.0f
                grade = 3.0f
                temperature = 21.toByte()
            },
            RecordMesg().apply {
                timestamp = DateTime(Date(BASE_MS + 1_000))
                speed = 6.0f
                power = 215
            },
        )

        val records = parser.parse(file)

        assertEquals(2, records.size)
        val first = records.first()
        assertEquals(BASE_MS, first.timestampMs)
        assertEquals(60.19, first.lat!!, 1e-6)
        assertEquals(24.94, first.lng!!, 1e-6)
        assertEquals(12.0, first.altitude!!, 1e-3)
        assertEquals(5.0, first.speed!!, 1e-3)
        assertEquals(210, first.power)
        assertEquals(140, first.heartRate)
        assertEquals(88, first.cadence)
        assertEquals(1234.0, first.distance!!, 1e-3)
        assertEquals(3.0, first.grade!!, 1e-3)
        assertEquals(21, first.temperature)
        assertEquals(BASE_MS + 1_000, records[1].timestampMs)
        assertEquals(215, records[1].power)
    }

    @Test
    fun `a record the file left empty stays null rather than zero`() {
        val file = writeFit(
            RecordMesg().apply {
                timestamp = DateTime(Date(BASE_MS))
                power = 200
            },
        )

        val record = parser.parse(file).single()

        assertEquals(200, record.power)
        assertNull(record.lat)
        assertNull(record.lng)
        assertNull(record.altitude)
        assertNull(record.speed)
        assertNull(record.heartRate)
        assertNull(record.cadence)
        assertNull(record.distance)
        assertNull(record.grade)
        assertNull(record.temperature)
    }

    @Test
    fun `a record without a timestamp is left out`() {
        val file = writeFit(
            RecordMesg().apply { temperature = 20.toByte() }, // no place on the timeline
            RecordMesg().apply {
                timestamp = DateTime(Date(BASE_MS))
                power = 210
            },
        )

        assertEquals(listOf(210), parser.parse(file).map { it.power })
    }

    @Test
    fun `a file with no records parses to nothing`() {
        assertEquals(emptyList<FitRecord>(), parser.parse(writeFit()))
    }

    @Test
    fun `semicircles convert to degrees across the range`() {
        assertEquals(0.0, FitParser.semicirclesToDegrees(0), 0.0)
        assertEquals(90.0, FitParser.semicirclesToDegrees(1 shl 30), 1e-9)
        assertEquals(-90.0, FitParser.semicirclesToDegrees(-(1 shl 30)), 1e-9)
        assertEquals(-180.0, FitParser.semicirclesToDegrees(Int.MIN_VALUE), 1e-9)
    }

    private fun writeFit(vararg records: RecordMesg): IoFile {
        val file = temp.newFile("ride.fit")
        val encoder = FileEncoder(file)
        encoder.write(
            FileIdMesg().apply {
                type = File.ACTIVITY
                manufacturer = Manufacturer.GARMIN
                product = 1
                serialNumber = 1L
                timeCreated = DateTime(Date(BASE_MS))
            },
        )
        records.forEach { encoder.write(it) }
        encoder.close()
        return file
    }

    private fun semicircles(degrees: Double): Int = (degrees * (1L shl 31) / 180.0).toInt()

    private companion object {
        /** Whole seconds, so the FIT second-resolution timestamp round-trips exactly. */
        const val BASE_MS = 1_700_000_000_000L
    }
}
