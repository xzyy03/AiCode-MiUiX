package com.muse.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDownscalerTest {
    @Test
    fun imageWithinLimitKeepsItsSize() {
        assertEquals(1200 to 800, ImageDownscaler.fitWithin(1200, 800))
    }

    @Test
    fun longEdgeIsScaledDownKeepingAspectRatio() {
        assertEquals(1568 to 1176, ImageDownscaler.fitWithin(4000, 3000))
        assertEquals(1176 to 1568, ImageDownscaler.fitWithin(3000, 4000))
    }

    @Test
    fun extremeAspectRatioNeverCollapsesToZero() {
        assertEquals(1568 to 1, ImageDownscaler.fitWithin(100_000, 10))
    }
}
