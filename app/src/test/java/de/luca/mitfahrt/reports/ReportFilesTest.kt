package de.luca.mitfahrt.reports

import android.app.Application
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReportFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var reports: ReportFiles
    private val id = "11111111-1111-4111-8111-111111111111"
    private val otherId = "22222222-2222-4222-8222-222222222222"

    @Before fun setUp() {
        val filesDir = temporary.root
        val app: Application = RuntimeEnvironment.getApplication()
        reports = ReportFiles(object : ContextWrapper(app) {
            override fun getFilesDir(): File = filesDir
        })
        directory = File(filesDir, "reports").apply { check(mkdir()) }
    }

    private fun pdf(reportId: String = id) = File(directory, "Mitfahrt-$reportId.pdf")
    private fun json(reportId: String = id) = File(directory, "$reportId.json")
    private fun writeReport(reportId: String = id) {
        pdf(reportId).writeText("PDF $reportId")
        json(reportId).writeText("JSON $reportId")
    }

    @Test fun `deleting a report removes both its PDF and metadata`() {
        writeReport()

        assertTrue(reports.delete(id))

        assertFalse(pdf().exists())
        assertFalse(json().exists())
        assertTrue(directory.isDirectory)
    }

    @Test fun `deleting one report preserves other reports and unrelated files`() {
        writeReport()
        writeReport(otherId)
        val unrelated = File(directory, "notes.txt").apply { writeText("keep notes") }
        val temporaryReport = File(directory, "$id.tmp").apply { writeText("keep temporary") }

        assertTrue(reports.delete(id))

        assertEquals("PDF $otherId", pdf(otherId).readText())
        assertEquals("JSON $otherId", json(otherId).readText())
        assertEquals("keep notes", unrelated.readText())
        assertEquals("keep temporary", temporaryReport.readText())
    }

    @Test fun `missing files and repeated deletion are successful`() {
        assertTrue(reports.delete(id))
        writeReport()
        assertTrue(reports.delete(id))
        assertTrue(reports.delete(id))
        assertTrue(directory.delete())
        assertTrue(reports.delete(id))
    }

    @Test fun `a missing PDF or metadata does not prevent deleting the remaining file`() {
        writeReport()
        assertTrue(pdf().delete())
        assertTrue(reports.delete(id))
        assertFalse(json().exists())

        writeReport()
        assertTrue(json().delete())
        assertTrue(reports.delete(id))
        assertFalse(pdf().exists())
    }

    @Test fun `invalid and traversal IDs are rejected before any deletion`() {
        writeReport()
        val outside = File(temporary.root, "outside.json").apply { writeText("outside") }
        val invalidIds = listOf("", "a".repeat(35), "a".repeat(37), "../outside", "../../$id",
            "$id/../outside", "/$id", id.replace('1', 'A'))

        invalidIds.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { reports.delete(invalid) }
            assertEquals("PDF $id", pdf().readText())
            assertEquals("JSON $id", json().readText())
            assertEquals("outside", outside.readText())
        }
    }

    @Test fun `PDF deletion failure is reported and metadata deletion is still attempted`() {
        assertTrue(pdf().mkdir())
        val protectedContents = File(pdf(), "keep.txt").apply { writeText("keep") }
        json().writeText("metadata")
        writeReport(otherId)

        assertFalse(reports.delete(id))

        assertEquals("keep", protectedContents.readText())
        assertFalse(json().exists())
        assertEquals("PDF $otherId", pdf(otherId).readText())
        assertEquals("JSON $otherId", json(otherId).readText())
    }

    @Test fun `metadata deletion failure is reported after deleting the PDF`() {
        pdf().writeText("PDF")
        assertTrue(json().mkdir())
        val protectedContents = File(json(), "keep.txt").apply { writeText("keep") }

        assertFalse(reports.delete(id))

        assertFalse(pdf().exists())
        assertEquals("keep", protectedContents.readText())
        assertFalse(reports.delete(id))
    }
}
