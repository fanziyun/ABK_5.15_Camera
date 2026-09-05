package com.abk.extension.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPatternProviderTest {
    @Test
    fun frameSizeMatchesYuyvLayout() {
        val provider = TestPatternProvider()
        val frame = provider.buildYuyv(640, 360, "mjpeg_1280x720", 0L)
        assertEquals(640 * 360 * 2, frame.size)
    }

    @Test
    fun frameNumberIncrements() {
        val provider = TestPatternProvider()
        val first = provider.nextFrameNumber()
        val second = provider.nextFrameNumber()
        assertEquals(first + 1, second)
    }

    @Test
    fun frameContainsNonZeroPixels() {
        val provider = TestPatternProvider()
        val frame = provider.buildYuyv(320, 240, "yuy2_640x360", 1234L)
        assertTrue(frame.any { it.toInt() != 0 })
    }
}
