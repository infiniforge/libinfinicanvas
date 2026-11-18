package com.infinifolder.preview

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class OpenRasterPackageTest {
    private fun zip(entries:Map<String,ByteArray>):ByteArray=ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> entries.forEach { (name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry() } } }.toByteArray()
    private fun entries(bytes:ByteArray):Map<String,ByteArray> = linkedMapOf<String,ByteArray>().also { result -> ZipInputStream(bytes.inputStream()).use { zip -> while(true) { val entry=zip.nextEntry ?: break;result[entry.name]=zip.readBytes() } } }
    @Test fun arbitraryEmbeddedTypesAndUnknownMetadataRoundTripWithoutRendering() {
        val xml="""<image w="32" h="16" xmlns:x="urn:example:extension" x:revision="7"><x:metadata keep="yes"/><stack><layer name="cycling" src="data/art.iff" x:mode="native"/><layer name="sound" src="sound/theme.unknown" type="audio/example"/></stack></image>""".toByteArray()
        val source=linkedMapOf("mimetype" to "image/openraster".toByteArray(),"stack.xml" to xml,"data/art.iff" to byteArrayOf(1,2,3),"sound/theme.unknown" to byteArrayOf(9,8,7),"other/extra.bin" to byteArrayOf(0,1,2),"mergedimage.png" to byteArrayOf(4,5))
        val project=OpenRasterPackage.read(zip(source))!!
        assertEquals(listOf("data/art.iff","sound/theme.unknown","other/extra.bin"),project.references.map { it.path })
        assertEquals("audio/example",project.references[1].mimeType)
        assertFalse(project.references.last().visible)
        val saved=ByteArrayOutputStream().also(project::write).toByteArray();val result=entries(saved)
        assertEquals(source.keys,result.keys)
        source.forEach { (name,bytes)->assertArrayEquals(name,bytes,result[name]) }
        ZipInputStream(saved.inputStream()).use { zip -> val first=zip.nextEntry;assertEquals("mimetype",first.name);assertEquals(ZipEntry.STORED,first.method) }
    }
    @Test fun nestedGroupsRetainPerAssetReferencesAndDocumentCoordinates() {
        val xml="""<image w="100" h="80"><stack x="2" y="3"><stack name="group" x="10" y="20"><layer src="a.svg" x="4" y="5"/></stack><layer src="b.dat"/></stack></image>""".toByteArray()
        val p=OpenRasterPackage.read(zip(mapOf("stack.xml" to xml,"a.svg" to byteArrayOf(1),"b.dat" to byteArrayOf(2))))!!
        assertEquals(2,p.references.size)
        assertEquals(16,p.references[0].x);assertEquals(28,p.references[0].y)
        assertEquals(1,p.references[0].parent);assertEquals(0,p.references[1].parent)
        assertEquals(2,p.references[1].x);assertEquals(3,p.references[1].y)
    }
    @Test fun unsafePathsAndExternalEntitiesAreRejected() {
        val safe="<image w='1' h='1'><stack/></image>".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { OpenRasterPackage.read(zip(mapOf("stack.xml" to safe,"../escape.dat" to byteArrayOf(1)))) }
        val unsafe="<!DOCTYPE image [<!ENTITY x SYSTEM 'file:///should-not-read'>]><image w='1' h='1'><stack/></image>"
        for(bytes in listOf(unsafe.toByteArray(),unsafe.toByteArray(Charsets.UTF_16))) {
            assertThrows(IllegalArgumentException::class.java) { OpenRasterPackage.read(zip(mapOf("stack.xml" to bytes))) }
        }
    }
    @Test fun priorLayerValueTypesAndPublicEntryPointsKeepTheirSignatures() {
        val layer=ImageLayer::class.java
        layer.getConstructor(String::class.java,String::class.java,android.graphics.Bitmap::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,String::class.java)
        layer.getMethod("copy",String::class.java,String::class.java,android.graphics.Bitmap::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,String::class.java)
        LayerDocument::class.java.getMethod("copy",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,List::class.java)
        LayerDocument::class.java.getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,List::class.java)
        LayerDocuments::class.java.getMethod("decode",ByteArray::class.java,Int::class.javaPrimitiveType)
        LayerDocuments::class.java.getMethod("encodeOra",LayerDocument::class.java)
        ImageSaving::class.java.getMethod("writeDocument",LayerDocument::class.java,String::class.java,java.io.OutputStream::class.java)
    }
}
