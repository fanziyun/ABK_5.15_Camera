package com.abk.extension.camera

import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-JVM test pattern generator used by the Camera2 fallback path.  It
 * produces a YUYV colour-bar frame with an embedded timestamp, frame number,
 * and current profile text.  The class is intentionally independent of Android
 * graphics APIs so it can be unit-tested on the host.
 */
internal class TestPatternProvider {
    private val frameNumber = AtomicLong(0L)

    fun buildYuyv(width: Int, height: Int, profileLabel: String, timestampMs: Long): ByteArray {
        require(width > 0 && height > 0)
        val size = width * height * 2
        val out = ByteArray(size)
        val bars = listOf(
            0xEB to 0x80, // white
            0x4C to 0x8B, // yellow
            0x52 to 0x8B, // cyan
            0x28 to 0x8B, // green
            0x6D to 0x28, // magenta
            0x8B to 0x28, // red
            0x0A to 0x8B, // blue
            0x00 to 0x80, // black
        )

        for (y in 0 until height) {
            val bar = bars[(y * bars.size) / height]
            for (x in 0 until width) {
                val index = (y * width + x) * 2
                out[index] = bar.first.toByte()
                out[index + 1] = bar.second.toByte()
            }
        }

        embedLabel(out, width, height, profileLabel, timestampMs)
        return out
    }

    fun nextFrameNumber(): Long = frameNumber.incrementAndGet()

    private fun embedLabel(frame: ByteArray, width: Int, height: Int, label: String, timestampMs: Long) {
        val text = label + " #" + frameNumber.get() + " " + timestampMs
        val baseline = (height / 2).coerceIn(8, height - 8)
        for ((offset, ch) in text.withIndex()) {
            val x = 8 + offset * 6
            if (x + 5 >= width) break
            drawGlyph(frame, width, x, baseline, ch)
        }
    }

    private fun drawGlyph(frame: ByteArray, width: Int, x: Int, y: Int, ch: Char) {
        val glyph = GLYPHS[ch] ?: return
        for (row in 0 until 5) {
            val bits = glyph[row]
            for (col in 0 until 5) {
                if (((bits shr (4 - col)) and 1) == 1) {
                    val px = x + col
                    val py = y + row
                    if (px >= 0 && px < width && py >= 0 && py < width) {
                        val index = (py * width + px) * 2
                        frame[index] = 0x00
                        frame[index + 1] = 0xFF.toByte()
                    }
                }
            }
        }
    }

    companion object {
        private val GLYPHS: Map<Char, IntArray> = mapOf(
            '0' to intArrayOf(0x1F, 0x11, 0x11, 0x11, 0x1F),
            '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x0E),
            '2' to intArrayOf(0x1F, 0x01, 0x1F, 0x10, 0x1F),
            '3' to intArrayOf(0x1F, 0x01, 0x0F, 0x01, 0x1F),
            '4' to intArrayOf(0x11, 0x11, 0x1F, 0x01, 0x01),
            '5' to intArrayOf(0x1F, 0x10, 0x1F, 0x01, 0x1F),
            '6' to intArrayOf(0x1F, 0x10, 0x1F, 0x11, 0x1F),
            '7' to intArrayOf(0x1F, 0x01, 0x01, 0x01, 0x01),
            '8' to intArrayOf(0x1F, 0x11, 0x1F, 0x11, 0x1F),
            '9' to intArrayOf(0x1F, 0x11, 0x1F, 0x01, 0x1F),
            'a' to intArrayOf(0x0E, 0x11, 0x1F, 0x11, 0x11),
            'b' to intArrayOf(0x1E, 0x11, 0x1E, 0x11, 0x1E),
            'c' to intArrayOf(0x0F, 0x10, 0x10, 0x10, 0x0F),
            'd' to intArrayOf(0x1E, 0x11, 0x11, 0x11, 0x1E),
            'e' to intArrayOf(0x1F, 0x10, 0x1E, 0x10, 0x1F),
            'f' to intArrayOf(0x1F, 0x10, 0x1E, 0x10, 0x10),
            'g' to intArrayOf(0x0F, 0x10, 0x17, 0x11, 0x0F),
            'h' to intArrayOf(0x11, 0x11, 0x1F, 0x11, 0x11),
            'i' to intArrayOf(0x0E, 0x04, 0x04, 0x04, 0x0E),
            'j' to intArrayOf(0x07, 0x02, 0x02, 0x12, 0x0C),
            'k' to intArrayOf(0x11, 0x12, 0x1C, 0x12, 0x11),
            'l' to intArrayOf(0x10, 0x10, 0x10, 0x10, 0x1F),
            'm' to intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11),
            'n' to intArrayOf(0x11, 0x19, 0x15, 0x13, 0x11),
            'o' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x0E),
            'p' to intArrayOf(0x1E, 0x11, 0x1E, 0x10, 0x10),
            'q' to intArrayOf(0x0E, 0x11, 0x15, 0x13, 0x0D),
            'r' to intArrayOf(0x1E, 0x11, 0x1E, 0x12, 0x11),
            's' to intArrayOf(0x0F, 0x10, 0x0E, 0x01, 0x1E),
            't' to intArrayOf(0x1F, 0x04, 0x04, 0x04, 0x04),
            'u' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x0E),
            'v' to intArrayOf(0x11, 0x11, 0x11, 0x0A, 0x04),
            'w' to intArrayOf(0x11, 0x15, 0x15, 0x15, 0x0A),
            'x' to intArrayOf(0x11, 0x0A, 0x04, 0x0A, 0x11),
            'y' to intArrayOf(0x11, 0x11, 0x0F, 0x01, 0x1E),
            'z' to intArrayOf(0x1F, 0x01, 0x0E, 0x10, 0x1F),
            ' ' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00),
            '-' to intArrayOf(0x00, 0x00, 0x0E, 0x00, 0x00),
            '_' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x1F),
            '.' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x04),
            '#' to intArrayOf(0x0A, 0x1F, 0x0A, 0x1F, 0x0A),
        )
    }
}
