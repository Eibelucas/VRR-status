package de.luca.mitfahrt.widgets

/** Layout decisions for one widget size in dp. Main information is never dropped, only extras. */
data class WidgetLayoutSpec(
    val rows: Int,
    /** Below about two cells of height the header gives its space to the departure itself. */
    val showHeader: Boolean,
    val showStatusLine: Boolean,
    val showDetail: Boolean,
    val showMap: Boolean
)

object WidgetSizing {
    private const val PADDING = 20
    private const val HEADER = 38
    private const val HEADER_WITH_STATUS = 44
    private const val ROW = 40
    private const val DETAIL_ROW = 52
    const val MAX_ROWS = 5
    const val MIN_HEIGHT_FOR_HEADER = 96

    fun spec(widthDp: Int, heightDp: Int, info: InfoLevel, kind: WidgetKind, rowLimit: Int = info.maxRows): WidgetLayoutSpec {
        val width = widthDp.coerceAtLeast(40)
        val height = heightDp.coerceAtLeast(40)
        val showHeader = height >= MIN_HEIGHT_FOR_HEADER
        val showStatusLine = showHeader && height >= 110
        val showDetail = info == InfoLevel.DETAILED && width >= 220 && height >= 160
        val rowHeight = if (showDetail) DETAIL_ROW else ROW
        val header = when { !showHeader -> 0; showStatusLine -> HEADER_WITH_STATUS; else -> HEADER }
        val fitting = ((height - PADDING - header) / rowHeight).coerceIn(1, MAX_ROWS)
        return WidgetLayoutSpec(
            rows = minOf(fitting, rowLimit.coerceIn(1, MAX_ROWS)),
            showHeader = showHeader,
            showStatusLine = showStatusLine,
            showDetail = showDetail,
            showMap = showHeader && kind == WidgetKind.ACTIVE && width >= 160
        )
    }
}
