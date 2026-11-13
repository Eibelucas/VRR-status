package de.luca.mitfahrt.reports

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/** Draws report blocks on A4 pages. Rows are never split; headings stay with their first row. */
object JourneyReportPdf {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    private const val LEFT = 48f
    private const val RIGHT = 547f
    private const val TOP = 56f
    private const val BOTTOM = 782f
    private val ink = Color.rgb(28, 32, 40)
    private val muted = Color.rgb(88, 92, 104)
    private val berry = Color.rgb(180, 0, 85)
    private val rule = Color.rgb(205, 205, 212)

    /** Where pages go: the PDF file on a device, bitmaps in layout tests. */
    interface Pages {
        fun start(number: Int): android.graphics.Canvas
        fun finish()
        fun close() {}
    }

    private class PdfPages(private val target: File) : Pages {
        private val document = PdfDocument()
        private var page: PdfDocument.Page? = null
        override fun start(number: Int): android.graphics.Canvas =
            document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()).also { page = it }.canvas
        override fun finish() { page?.let(document::finishPage); page = null }
        fun write() = target.outputStream().use(document::writeTo)
        override fun close() = document.close()
    }

    /** Writes the PDF and returns the number of pages. */
    fun render(blocks: List<ReportBlock>, target: File): Int {
        val pages = PdfPages(target)
        try {
            val count = render(blocks, pages)
            pages.write()
            return count
        } finally { pages.close() }
    }

    fun render(blocks: List<ReportBlock>, pages: Pages): Int {
        run {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var number = 0
            var canvas: android.graphics.Canvas? = null
            var y = TOP
            fun style(size: Float, bold: Boolean = false, color: Int = ink) {
                paint.textSize = size; paint.color = color
                paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            fun footer() {
                style(8f, color = muted)
                canvas!!.drawText("Mitfahrt · Fahrtbericht · Seite $number", LEFT, 812f, paint)
            }
            fun newPage() {
                if (canvas != null) { footer(); pages.finish() }
                canvas = pages.start(++number)
                y = TOP
            }
            fun wrap(text: String, width: Float): List<String> {
                val lines = mutableListOf<String>()
                for (paragraph in text.split('\n')) {
                    var remaining = paragraph.trim()
                    if (remaining.isEmpty()) continue
                    while (remaining.isNotEmpty()) {
                        val count = paint.breakText(remaining, true, width, null).coerceIn(1, remaining.length)
                        val end = if (count < remaining.length) remaining.lastIndexOf(' ', count).takeIf { it > 0 } ?: count else count
                        lines += remaining.take(end)
                        remaining = remaining.drop(end).trimStart()
                    }
                }
                return lines.ifEmpty { listOf("") }
            }
            fun need(height: Float) { if (canvas == null || y + height > BOTTOM) newPage() }
            /** Draws wrapped text in a column and returns its height; the caller reserves the space first. */
            fun column(text: String, x: Float, width: Float, size: Float, bold: Boolean = false, color: Int = ink, draw: Boolean = true): Float {
                style(size, bold, color)
                val lines = wrap(text, width)
                val leading = size * 1.38f
                if (draw) lines.forEachIndexed { index, line -> canvas!!.drawText(line, x, y + size + index * leading, paint) }
                return lines.size * leading
            }
            fun line(color: Int = rule) {
                paint.color = color; paint.strokeWidth = 0.7f
                canvas!!.drawLine(LEFT, y, RIGHT, y, paint)
            }
            fun paragraph(text: String, size: Float, bold: Boolean = false, color: Int = ink, gap: Float = 4f) {
                style(size, bold, color)
                // Long texts may flow over a page break line by line.
                val leading = size * 1.38f
                wrap(text, RIGHT - LEFT).forEach { row ->
                    need(leading)
                    style(size, bold, color)
                    canvas!!.drawText(row, LEFT, y + size, paint)
                    y += leading
                }
                y += gap
            }
            val nameWidth = 235f; val timeX = LEFT + 245f; val forecastX = LEFT + 315f; val noteX = LEFT + 400f

            newPage()
            blocks.forEachIndexed { index, block ->
                when (block) {
                    is ReportBlock.Title -> {
                        style(10f, true, berry); canvas!!.drawText("MITFAHRT", LEFT, y, paint); y += 12f
                        y += column(block.text, LEFT, RIGHT - LEFT, 24f, bold = true) + 2f
                        y += column(block.subtitle, LEFT, RIGHT - LEFT, 13f, color = muted) + 12f
                    }
                    is ReportBlock.Heading -> {
                        // Keep a heading together with what follows it.
                        need(64f); y += 10f
                        y += column(block.text, LEFT, RIGHT - LEFT, 14f, bold = true) + 3f
                        line(berry); y += 8f
                    }
                    is ReportBlock.Paragraph -> paragraph(block.text, 11f, bold = block.strong)
                    is ReportBlock.Small -> paragraph(block.text, 8.5f, color = muted, gap = 3f)
                    is ReportBlock.Field -> {
                        val height = column(block.value, LEFT + 110f, RIGHT - LEFT - 110f, 11f, draw = false)
                        need(height + 4f)
                        column(block.label, LEFT, 104f, 9f, color = muted)
                        y += column(block.value, LEFT + 110f, RIGHT - LEFT - 110f, 11f, bold = true) + 4f
                    }
                    is ReportBlock.Event -> {
                        val textHeight = column(block.text, LEFT + 52f, 290f, 10.5f, draw = false)
                        val sourceHeight = column(block.source, LEFT + 352f, RIGHT - LEFT - 352f, 9f, draw = false)
                        val height = maxOf(textHeight, sourceHeight)
                        need(height + 6f)
                        column(block.time, LEFT, 48f, 10.5f, bold = true)
                        column(block.text, LEFT + 52f, 290f, 10.5f)
                        column(block.source, LEFT + 352f, RIGHT - LEFT - 352f, 9f, color = muted)
                        y += height + 6f
                    }
                    is ReportBlock.LegHeader -> {
                        need(70f); y += 6f
                        y += column(block.text, LEFT, RIGHT - LEFT, 12f, bold = true)
                        if (block.detail.isNotBlank()) y += column(block.detail, LEFT, RIGHT - LEFT, 9f, color = muted)
                        y += 4f
                    }
                    is ReportBlock.StopHeader -> {
                        need(40f)
                        column(block.name, LEFT, nameWidth, 8.5f, color = muted)
                        column(block.planned, timeX, 66f, 8.5f, color = muted)
                        y += column(block.forecast, forecastX, 80f, 8.5f, color = muted) + 2f
                        line(); y += 4f
                    }
                    is ReportBlock.StopRow -> {
                        val nameHeight = column(block.name, LEFT, nameWidth, 10.5f, draw = false)
                        val noteHeight = if (block.note.isBlank()) 0f else column(block.note, noteX, RIGHT - noteX, 9f, draw = false)
                        val height = maxOf(nameHeight, noteHeight)
                        need(height + 3f)
                        column(block.name, LEFT, nameWidth, 10.5f)
                        column(block.planned, timeX, 66f, 10.5f)
                        column(block.forecast, forecastX, 80f, 10.5f, bold = block.forecast != "–")
                        if (block.note.isNotBlank()) column(block.note, noteX, RIGHT - noteX, 9f, color = muted)
                        y += height + 3f
                        if (blocks.getOrNull(index + 1) !is ReportBlock.StopRow) y += 4f
                    }
                }
            }
            footer()
            pages.finish()
            return number
        }
    }
}
