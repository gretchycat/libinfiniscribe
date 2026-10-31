package io.github.gretchycat.infiniscribe

import org.junit.Assert.*
import org.junit.Test

class PageRasterSizeTest {
    @Test fun portraitRetainsExistingTwoTimesRendering() {
        assertEquals(PageRasterSize(1224, 1584), PageRasterSize.fit(612, 792, 1080))
    }
    @Test fun landscapeRetainsExistingTwoTimesRendering() {
        assertEquals(PageRasterSize(1584, 1224), PageRasterSize.fit(792, 612, 1080))
    }
    @Test fun wideViewportRaisesResolution() {
        assertEquals(PageRasterSize(2448, 3168), PageRasterSize.fit(612, 792, 2448))
    }
    @Test fun oversizedPortraitKeepsAspectRatio() {
        assertEquals(PageRasterSize(1024, 4096), PageRasterSize.fit(2000, 8000, 1080))
    }
    @Test fun oversizedLandscapeKeepsAspectRatio() {
        assertEquals(PageRasterSize(4096, 1024), PageRasterSize.fit(8000, 2000, 1080))
    }
    @Test fun extremeSizesDoNotOverflowOrAllocateZeroPixels() {
        assertEquals(PageRasterSize(4096, 1), PageRasterSize.fit(Int.MAX_VALUE, 1, Int.MAX_VALUE))
        assertEquals(PageRasterSize(1, 4096), PageRasterSize.fit(1, Int.MAX_VALUE, 1080))
    }
    @Test fun callerCanSetSmallerRasterBudget() {
        assertEquals(PageRasterSize(512, 1024), PageRasterSize.fit(600, 1200, 1080, 1024))
    }
    @Test fun invalidDimensionsFailBeforeAllocation() {
        for (args in listOf(intArrayOf(0, 1, 1, 4096), intArrayOf(1, -1, 1, 4096),
            intArrayOf(1, 1, 0, 4096), intArrayOf(1, 1, 1, 0))) {
            assertThrows(IllegalArgumentException::class.java) { PageRasterSize.fit(args[0], args[1], args[2], args[3]) }
        }
    }
}
