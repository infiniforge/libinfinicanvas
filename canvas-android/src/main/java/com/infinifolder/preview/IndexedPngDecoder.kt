package com.infinifolder.preview

import com.infinifolder.preview.ImageDecodeUtils.u32be
import com.infinifolder.preview.ImageDecodeUtils.u8
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/** Keeps indexed PNG samples and palette registers intact for formats such as extended ORA. */
internal object IndexedPngDecoder {
    data class Image(val width: Int, val height: Int, val indices: ByteArray, val palette: IntArray, val paletteSize: Int)

    /** Small, payload-free PNG inventory for diagnosing unsupported indexed layers. */
    fun headerSummary(bytes: ByteArray): String {
        if (bytes.size < 8 || bytes[0] != 0x89.toByte() || bytes[1] != 0x50.toByte() || bytes[2] != 0x4e.toByte()) {
            return "signature=invalid bytes=${bytes.size}"
        }
        var width = 0
        var height = 0
        var depth = -1
        var colorType = -1
        var interlace = -1
        var paletteEntries = -1
        val chunks = mutableListOf<String>()
        var offset = 8
        while (offset + 12 <= bytes.size && chunks.size < 32) {
            val length = u32be(bytes, offset)
            if (length < 0 || length > bytes.size - offset - 12) {
                chunks += "truncated"
                break
            }
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            chunks += "$type:$length"
            val data = offset + 8
            when (type) {
                "IHDR" -> if (length == 13L) {
                    width = u32be(bytes, data).toInt(); height = u32be(bytes, data + 4).toInt()
                    depth = u8(bytes, data + 8); colorType = u8(bytes, data + 9); interlace = u8(bytes, data + 12)
                }
                "PLTE" -> paletteEntries = (length / 3).toInt()
            }
            offset += length.toInt() + 12
            if (type == "IEND") break
        }
        return "bytes=${bytes.size} ${width}x$height depth=$depth colorType=$colorType " +
            "interlace=$interlace paletteEntries=$paletteEntries chunks=${chunks.joinToString(",")}"
    }

    fun decode(bytes: ByteArray, maxPixels: Int = 4_000_000): Image? {
        if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return null
        var width = 0
        var height = 0
        var depth = 0
        var colorType = -1
        var interlace = -1
        var sawHeader = false
        var sawPalette = false
        var sawData = false
        var sawEnd = false
        var palette = IntArray(0)
        var alpha = ByteArray(0)
        val compressed = ByteArrayOutputStream()
        var offset = 8
        try {
            while (offset + 12 <= bytes.size) {
                val length = u32be(bytes, offset)
                if (length < 0 || length > 16 * 1024 * 1024 || length > bytes.size - offset - 12) return null
                val start = offset + 8
                val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                val checksum = CRC32().apply { update(bytes, offset + 4, length.toInt() + 4) }.value
                if (checksum != u32be(bytes, start + length.toInt())) return null
                when (type) {
                    "IHDR" -> {
                        if (length != 13L || offset != 8 || sawHeader) return null
                        sawHeader = true
                        width = u32be(bytes, start).toInt()
                        height = u32be(bytes, start + 4).toInt()
                        depth = u8(bytes, start + 8)
                        colorType = u8(bytes, start + 9)
                        if (u8(bytes, start + 10) != 0 || u8(bytes, start + 11) != 0) return null
                        interlace = u8(bytes, start + 12)
                    }
                    "PLTE" -> {
                        if (!sawHeader || sawData || sawPalette || length !in 3L..768L || length % 3L != 0L) return null
                        sawPalette = true
                        palette = IntArray((length / 3).toInt()) { i ->
                            val p = start + i * 3
                            0xff000000.toInt() or (u8(bytes, p) shl 16) or (u8(bytes, p + 1) shl 8) or u8(bytes, p + 2)
                        }
                    }
                    "tRNS" -> {
                        if (!sawPalette || sawData || length > 256) return null
                        alpha = bytes.copyOfRange(start, start + length.toInt())
                    }
                    "IDAT" -> {
                        if (!sawPalette) return null
                        sawData = true
                        if (compressed.size().toLong() + length > 16L * 1024 * 1024) return null
                        compressed.write(bytes, start, length.toInt())
                    }
                    "IEND" -> {
                        if (length != 0L || !sawData) return null
                        sawEnd = true
                        break
                    }
                }
                offset += length.toInt() + 12
            }
            if (width <= 0 || height <= 0 || width.toLong() * height > maxPixels ||
                !sawHeader || !sawEnd || colorType != 3 || depth !in setOf(1, 2, 4, 8) || interlace != 0 || palette.isEmpty()) return null
            if (palette.size > (1 shl depth) || alpha.size > palette.size) return null
            val rowBytes = (width * depth + 7) / 8
            val expected = height.toLong() * (rowBytes + 1L)
            if (expected > Int.MAX_VALUE) return null
            val raw = InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { input ->
                val out = ByteArrayOutputStream(expected.toInt())
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size().toLong() + count > expected) return null
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            if (raw.size != expected.toInt()) return null
            val indices = ByteArray(width * height)
            val previous = ByteArray(rowBytes)
            val current = ByteArray(rowBytes)
            var inputOffset = 0
            val filterBpp = 1 // PNG filter bytes-per-pixel is rounded up for sub-byte samples.
            for (y in 0 until height) {
                val filter = u8(raw, inputOffset++)
                for (x in 0 until rowBytes) {
                    val value = u8(raw, inputOffset++)
                    val left = if (x >= filterBpp) u8(current, x - filterBpp) else 0
                    val above = u8(previous, x)
                    val upperLeft = if (x >= filterBpp) u8(previous, x - filterBpp) else 0
                    val predictor = when (filter) {
                        0 -> 0
                        1 -> left
                        2 -> above
                        3 -> (left + above) ushr 1
                        4 -> {
                            val p = left + above - upperLeft
                            val pa = abs(p - left); val pb = abs(p - above); val pc = abs(p - upperLeft)
                            if (pa <= pb && pa <= pc) left else if (pb <= pc) above else upperLeft
                        }
                        else -> return null
                    }
                    current[x] = (value + predictor).toByte()
                }
                for (x in 0 until width) {
                    val bit = x * depth
                    val shift = 8 - depth - (bit and 7)
                    val index = (u8(current, bit ushr 3) ushr shift) and ((1 shl depth) - 1)
                    if (index >= palette.size) return null
                    indices[y * width + x] = index.toByte()
                }
                current.copyInto(previous)
            }
            val colors = IntArray(256) { 0xff000000.toInt() }
            palette.copyInto(colors)
            for (i in alpha.indices) colors[i] = (colors[i] and 0x00ffffff) or (u8(alpha, i) shl 24)
            return Image(width, height, indices, colors, palette.size)
        } catch (_: IOException) {
            return null
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
    }
}
