package de.luca.mitfahrt.reports

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import androidx.core.content.FileProvider
import de.luca.mitfahrt.data.Departure
import de.luca.mitfahrt.data.Ride
import de.luca.mitfahrt.data.RideStop
import java.io.File
import java.time.Instant

/** Device test: real Android PDF writer, renderer, FileProvider and share sheet. Synthetic data only. */
class ReportPdfInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val directory = File(targetContext.filesDir, "reports/instrumented")
        try {
            val now = Instant.parse("2026-11-13T07:00:00Z")
            directory.deleteRecursively()
            val store = JourneyReportStore(directory, { ReportCustomization() }, clock = { now })
            val stops = (0..65).map { i ->
                val at = now.plusSeconds(i * 120L)
                RideStop("synthetic-$i", "Testhaltestelle mit langem Namen Nummer $i", null, at, at)
            }
            val departure = Departure("test:019", "019", "Testziel", stops.first().id, stops.first().name,
                "instrumented", now, null, "1")
            val ride = Ride(departure, stops, 0, now, boarded = false, trackingStartedAt = now)
            val report = checkNotNull(store.sync(ReportSnapshot(ride), true))
            val pdf = store.export(report.id)
            check(pdf.readBytes().take(5).toByteArray().decodeToString() == "%PDF-")
            val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.reports", pdf)
            val descriptor = checkNotNull(targetContext.contentResolver.openFileDescriptor(uri, "r"))
            var pageCount = 0
            PdfRenderer(descriptor).use { renderer ->
                pageCount = renderer.pageCount
                check(pageCount >= 2)
                for (index in 0 until pageCount) renderer.openPage(index).use { page ->
                    val image = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    image.eraseColor(android.graphics.Color.WHITE)
                    page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    check((0 until image.height step 4).any { y ->
                        (0 until image.width step 4).any { x -> image.getPixel(x, y) != android.graphics.Color.WHITE }
                    })
                    image.recycle()
                }
            }
            val frozen = pdf.readBytes()
            check(store.observe(report.id, "Eigene Testangabe ohne gemeldeten Ausfall"))
            val revised = store.export(report.id)
            check(revised != pdf && frozen.contentEquals(pdf.readBytes()))
            val share = Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = android.content.ClipData.newRawUri("Fahrtbericht", uri) }
            val activity = startActivitySync(Intent(targetContext, de.luca.mitfahrt.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            activity.runOnUiThread { activity.startActivity(Intent.createChooser(share, "Testbericht teilen")) }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var visible = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val activities = activityDump()
                if (activities.lineSequence().any { it.contains("mResumedActivity") && it.contains("ChooserActivity") }) {
                    visible = true
                    break
                }
                android.os.SystemClock.sleep(100)
            }
            check(visible) { "Share sheet was not visible" }
            saveScreenshot(directory)
            result.putString("result", "PASS: PDF written, $pageCount pages opened and rendered through FileProvider, frozen export preserved, share sheet visibly verified")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            runCatching { saveScreenshot(directory) }
            result.putString("failure", e.stackTraceToString())
            result.putString("activeWindow", runCatching { activityDump().lineSequence().filter { it.contains("Resumed") || it.contains("mFocused") }.joinToString("\n") }.getOrDefault("unavailable"))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun activityDump(): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand("dumpsys activity activities")).bufferedReader().use { it.readText() }

    private fun saveScreenshot(directory: File) {
        val screenshot = checkNotNull(uiAutomation.takeScreenshot())
        File(directory, "share.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
