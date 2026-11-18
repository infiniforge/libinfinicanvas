package com.infinifolder.preview

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRasterArchiveTest {
    @Test fun readsFlatLayerStackAndKeepsAnimationSourceEntries() {
        val stack = """<image w="320" h="200"><stack>
            <layer name="top" src="data/top.gif" x="4" y="7" opacity="0.5" visibility="visible"/>
            <layer name="base" src="data/base.png" x="0" y="0"/>
        </stack></image>""".toByteArray()
        val entries = archive("stack.xml" to stack, "data/top.gif" to byteArrayOf(1, 2),
            "data/base.png" to byteArrayOf(3), "preview.png" to byteArrayOf(4),
            "mergedimage.png" to byteArrayOf(5))

        val decoded = OpenRasterArchive.readEntries(entries, retainMedia = true)!!
        val document = OpenRasterArchive.parseStack(decoded["stack.xml"]!!)!!

        assertEquals(320, document.width)
        assertEquals(200, document.height)
        assertEquals(2, document.layers.size)
        assertEquals("data/top.gif", document.layers[0].src)
        assertEquals(4, document.layers[0].x)
        assertEquals(7, document.layers[0].y)
        assertEquals(0.5f, document.layers[0].opacity)
        assertTrue(document.layers[0].visible)
        assertEquals(byteArrayOf(1, 2).toList(), decoded["data/top.gif"]!!.toList())
        assertNull(decoded["preview.png"])
        assertNotNull(decoded["mergedimage.png"])
    }

    @Test fun rejectsDtdAndExternalEntities() {
        val xml = """<!DOCTYPE image [<!ENTITY ext SYSTEM "file:///etc/passwd">]>
            <image w="1" h="1"><stack><layer src="data/a.png" name="&ext;"/></stack></image>"""
        assertNull(OpenRasterArchive.parseStack(xml.toByteArray()))
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
