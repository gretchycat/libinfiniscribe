package io.github.gretchycat.infiniscribe

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rasterize a complete page with one scale, bounded on both axes. Units may be PDF points. */
data class PageRasterSize(val width: Int, val height: Int) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun fit(pageWidth: Int, pageHeight: Int, viewportWidth: Int, maxDimension: Int = 4096): PageRasterSize {
            require(pageWidth > 0 && pageHeight > 0) { "Page dimensions must be positive" }
            require(viewportWidth > 0 && maxDimension > 0) { "Raster bounds must be positive" }
            val desired = max(2.0, viewportWidth.toDouble() / pageWidth)
            val scale = min(desired, min(maxDimension.toDouble() / pageWidth, maxDimension.toDouble() / pageHeight))
            return PageRasterSize((pageWidth * scale).roundToInt().coerceIn(1, maxDimension),
                (pageHeight * scale).roundToInt().coerceIn(1, maxDimension))
        }
    }
}
