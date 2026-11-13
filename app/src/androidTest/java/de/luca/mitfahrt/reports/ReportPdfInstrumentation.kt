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
        try {
            val now = Instant.parse("2026-11-13T07:00:00Z")
            val directory = File(targetContext.filesDir, "reports/instrumented").apply { deleteRecursively() }
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
            targetContext.startActivity(Intent.createChooser(share, "Testbericht teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            result.putString("result", "PASS: PDF written, $pageCount pages opened and rendered through FileProvider, frozen export preserved, share sheet launched")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("failure", e.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
