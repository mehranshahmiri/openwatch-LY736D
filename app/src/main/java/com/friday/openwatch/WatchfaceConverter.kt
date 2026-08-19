package com.friday.openwatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color

/**
 * Builds the exact payload LaxaFit Pro sends for algorithm-2 devices (confirmed for
 * this watch: LJ736 / 240x296 / ALGORITHM=2, read from the app's own local DB).
 *
 * Full pipeline, reverse-engineered from xfkj.fitpro.utils.WatchThemeTools.startFile()
 * and confirmed by reassembling a real captured BLE transfer:
 *   1. RGB565 raw pixel data (bottom-up row order, matching a standard BMP).
 *   2. rotatDerection() transform (byte-for-byte ported from the real app below).
 *   3. Prefixed with a 6-byte proprietary header: 1601 + width_LE(2B) + height_LE(2B) + 00000a00.
 *   4. The watch's own font/glyph binary (bundled as res/raw/watch_font.bin, pulled
 *      directly from LaxaFit Pro's cache - it's a fixed asset per watch model, not
 *      generated per-image) is prepended to the whole thing.
 */
object WatchfaceConverter {

    fun buildDialFile(ctx: Context, bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val pixels = toRgb565BottomUp(bitmap, width, height)
        val rotated = rotatDerection(width, height, pixels)
        val header = byteArrayOf(
            0x16, 0x01,
            (width and 0xFF).toByte(), ((width shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte(),
            0x00, 0x00, 0x0A, 0x00,
        )
        val picWithHeader = header + rotated
        val font = ctx.resources.openRawResource(R.raw.watch_font).use { it.readBytes() }
        if (font.size < 100) {
            throw IllegalStateException(
                "Custom watch face is disabled: res/raw/watch_font.bin is a placeholder. " +
                    "Pull the real font asset from your own watch's official app cache " +
                    "(see README) and rebuild to enable this feature."
            )
        }
        return font + picWithHeader
    }

    private fun toRgb565BottomUp(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap

        val pixels = ByteArray(width * height * 2)
        var offset = 0
        for (y in height - 1 downTo 0) {
            for (x in 0 until width) {
                val p = scaled.getPixel(x, y)
                val r = Color.red(p) shr 3
                val g = Color.green(p) shr 2
                val b = Color.blue(p) shr 3
                val rgb565 = (r shl 11) or (g shl 5) or b
                pixels[offset] = (rgb565 and 0xFF).toByte()
                pixels[offset + 1] = ((rgb565 shr 8) and 0xFF).toByte()
                offset += 2
            }
        }
        return pixels
    }

    /** Direct port of WatchThemeTools.rotatDerection(int, int, byte[]). */
    private fun rotatDerection(width: Int, height: Int, data: ByteArray): ByteArray {
        val length = data.size
        val reversed = ByteArray(length)
        var srcIdx = length - 1
        var dstIdx = 0
        while (srcIdx >= 0) {
            reversed[dstIdx] = data[srcIdx]
            srcIdx--
            dstIdx++
        }
        val out = ByteArray(length)
        val rowBytes = width * 2
        for (row in 0 until height) {
            for (col in 0 until rowBytes) {
                val rowStart = row * rowBytes
                val rowEndIdx = rowStart + rowBytes - 1
                if (col % 2 == 1) {
                    val srcPos = rowEndIdx - col
                    val b = reversed[srcPos]
                    val dstPos = rowStart + col
                    out[dstPos] = reversed[srcPos + 1]
                    out[dstPos - 1] = b
                }
            }
        }
        return out
    }

    /** Splits into (seq, chunkBytes) pairs matching the real app's chunk size. */
    fun chunk(file: ByteArray, chunkSize: Int = Protocol.Dial.CHUNK_DATA_SIZE): List<Pair<Int, ByteArray>> {
        val chunks = mutableListOf<Pair<Int, ByteArray>>()
        var seq = 0
        var offset = 0
        while (offset < file.size) {
            val end = (offset + chunkSize).coerceAtMost(file.size)
            chunks.add(seq to file.copyOfRange(offset, end))
            offset = end
            seq++
        }
        return chunks
    }

    /** 32-bit running sum over every byte of the file, matching the real FINISH checksum. */
    fun totalChecksum(file: ByteArray): Int {
        var sum = 0
        for (b in file) sum += (b.toInt() and 0xFF)
        return sum
    }
}
