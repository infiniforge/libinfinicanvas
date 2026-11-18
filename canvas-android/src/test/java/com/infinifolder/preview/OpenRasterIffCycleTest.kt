package com.infinifolder.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRasterIffCycleTest {
    @Test fun rejectsDocumentTypeDeclarations() {
        val xml = """<!DOCTYPE image [<!ENTITY cycle "blocked">]>
            <image w="1" h="1"><stack><layer src="&cycle;"/></stack></image>""".toByteArray()
        assertNull(OpenRasterIffCycle.parseStack(xml))
    }

    @Test fun parsesVisibleIffLayer() {
        val xml = """<?xml version="1.0"?>
            <image w="275" h="116">
              <stack>
                <layer name="cycling" src="data/rainbow-logo.iff" x="0" y="0" opacity="1" visibility="visible" composite-op="svg:src-over"/>
                <layer name="base" src="data/base.png" x="0" y="0"/>
              </stack>
            </image>""".trimIndent().toByteArray()

        val parsed = OpenRasterIffCycle.parseStack(xml)

        assertEquals(275, parsed?.width)
        assertEquals(116, parsed?.height)
        assertEquals("data/rainbow-logo.iff", parsed?.layers?.firstOrNull()?.src)
        assertTrue(parsed?.layers?.firstOrNull()?.visible == true)
    }

    @Test fun fallbackDiagnosticReportsWhenNoVisibleCyclingIffLayerExists() {
        val stack = """<image w="640" h="480"><stack><layer name="background" src="data/base.png" x="0" y="0"/></stack></image>"""
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("stack.xml"))
                zip.write(stack.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val attempt = OpenRasterIffCycle.decodeWithDiagnostics(bytes, 512)

        assertNull(attempt.decoded)
        assertTrue(attempt.diagnostic.contains("reason=visible-cycling-iff-layer-not-found"))
        assertTrue(attempt.diagnostic.contains("canvas=640x480"))
        assertTrue(attempt.diagnostic.contains("data/base.png"))
        assertTrue(attempt.diagnostic.contains("stack.xml="))
    }
}
