package com.infinifolder.preview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRasterPaletteCycleTest {
    @Test fun fallbackDiagnosticReportsStackAndCycleLayerInventory() {
        val stack = """<image w="640" h="480"><stack><layer name="background" src="data/base.png" x="0" y="0"/></stack></image>"""
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("stack.xml"))
                zip.write(stack.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val attempt = OpenRasterPaletteCycle.decodeWithDiagnostics(bytes, 512)

        assertNull(attempt.decoded)
        assertTrue(attempt.diagnostic.contains("result=static-fallback reason=cycle-layer-count-0"))
        assertTrue(attempt.diagnostic.contains("canvas=640x480"))
        assertTrue(attempt.diagnostic.contains("data/base.png"))
        assertTrue(attempt.diagnostic.contains("stack.xml="))
    }

    @Test fun parsesUnqualifiedAttributesOnNamespacedExtensionElements() {
        val xml = """<?xml version="1.0"?>
            <image xmlns:im="${OpenRasterPaletteCycle.NAMESPACE}" w="275" h="116">
              <stack>
                <layer name="cycling" src="data/cycle.png" x="12" y="20" opacity="1" visibility="visible" composite-op="svg:src-over">
                  <im:palette-cycle enabled="true" mode="rotate" interval-ms="60" step="2" direction="reverse" loop="true" transparent-index="0" palette-entry-count="16">
                    <im:range start="1" end="8"/><im:range start="9" end="15"/>
                  </im:palette-cycle>
                </layer>
                <layer name="base" src="data/base.png" x="0" y="0"/>
              </stack>
            </image>""".trimIndent().toByteArray()

        val parsed = OpenRasterPaletteCycle.parseStack(xml)

        assertNotNull(parsed)
        assertEquals(275, parsed!!.width)
        assertEquals(116, parsed.height)
        val cycle = parsed.layers.first().cycle
        assertNotNull(cycle)
        assertTrue(cycle!!.enabled)
        assertEquals(60.0, cycle.intervalMillis, 0.0)
        assertEquals(2, cycle.step)
        assertTrue(cycle.reverse)
        assertEquals(0, cycle.transparentIndex)
        assertEquals(listOf(OpenRasterPaletteCycle.Range(1, 8), OpenRasterPaletteCycle.Range(9, 15)), cycle.ranges)
        assertNull(parsed.layers.last().cycle)
    }

    @Test fun paletteRangesRotateInTheSpecifiedDirectionWithoutChangingIndices() {
        val indexed = IndexedPngDecoder.Image(2, 1, byteArrayOf(1, 2), intArrayOf(
            0x00000000, 0xffff0000.toInt(), 0xff00ff00.toInt()
        ) + IntArray(253), 3)
        val cycle = OpenRasterPaletteCycle.Cycle(true, 100.0, 1, false, true, 0, 3,
            listOf(OpenRasterPaletteCycle.Range(1, 2)))
        val layer = OpenRasterPaletteCycle.Layer("cycle.png", 0, 0, 1f, true, "svg:src-over")
        val animation = OpenRasterPaletteCycle.PaletteAnimation(2, 1,
            intArrayOf(0xff0000ff.toInt(), 0xff0000ff.toInt()), intArrayOf(0, 0), layer,
            indexed, cycle, 1f, 255)
        val frame = IntArray(2)

        animation.render(0, frame, true)
        assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), frame)
        animation.render(100_000_000, frame, true)
        assertArrayEquals(intArrayOf(0xff00ff00.toInt(), 0xffff0000.toInt()), frame)
    }

    @Test fun indexedPngDecoderRejectsNonIndexedImages() {
        assertNull(IndexedPngDecoder.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun indexedPngDecoderPreservesPaletteIndicesAndTransparency() {
        val decoded = IndexedPngDecoder.decode(indexedPng())
        assertNotNull(decoded)
        assertEquals(2, decoded!!.width)
        assertEquals(1, decoded.height)
        assertArrayEquals(byteArrayOf(1, 2), decoded.indices)
        assertEquals(3, decoded.paletteSize)
        assertEquals(0, decoded.palette[0] ushr 24)
        assertEquals(0xffff0000.toInt(), decoded.palette[1])
        assertEquals(0xff00ff00.toInt(), decoded.palette[2])
    }

    private fun indexedPng(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        bytes.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        fun chunk(name: String, data: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(type); update(data) }.value.toInt()
            val size = data.size
            bytes.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            bytes.write(type); bytes.write(data)
            bytes.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        }
        chunk("IHDR", byteArrayOf(0, 0, 0, 2, 0, 0, 0, 1, 8, 3, 0, 0, 0))
        chunk("PLTE", byteArrayOf(0, 0, 0, 255.toByte(), 0, 0, 0, 255.toByte(), 0))
        chunk("tRNS", byteArrayOf(0))
        val compressed = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(byteArrayOf(0, 1, 2)) }
        }.toByteArray()
        chunk("IDAT", compressed); chunk("IEND", byteArrayOf())
        return bytes.toByteArray()
    }
}
