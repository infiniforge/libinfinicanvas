package com.infinifolder.preview

import io.github.gretchycat.infinistream.*
import java.io.ByteArrayOutputStream
import java.util.zip.*
import org.junit.Assert.*
import org.junit.Test

class AnimationProjectHeaderTest {
    private fun encoded(xml:String)=ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        for((name,data) in mapOf("mimetype" to AnimationProjects.MIME_TYPE.toByteArray(),"animation.xml" to xml.toByteArray())) {
            zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()
        }
    } }.toByteArray()
    private fun metadata(bytes:ByteArray):MetadataRecord? {
        val source=object:SourceResource {
            override val metadata=StreamMetadata(length=bytes.size.toLong(),name="project.icanim")
            override fun open()=InputStreamSource(bytes.inputStream(),metadata)
        }
        return MetadataReadContext(CapabilityResource(source.metadata,source=source)).use { AnimationProjectHandler().extractMetadata(it) }
    }
    @Test fun formatIdentityAndMetadataUseTimelineWithoutDecodingFramePixels() {
        val bytes=encoded("<animation version='1' w='320' h='200' plays='2'><frame id='a' src='frames/a.ora' duration-ms='40'/><frame id='b' src='frames/b.ora' duration-ms='90'/></animation>")
        assertEquals("icanim",ImageSignatures.extension(bytes))
        val fields=metadata(bytes)!!.fields
        assertEquals("320",fields["width"]);assertEquals("200",fields["height"])
        assertEquals("2",fields["frameCount"]);assertEquals("130",fields["durationMillis"]);assertEquals("2",fields["plays"])
    }
    @Test fun resourceProbesKeepExecutableDecodeThumbnailAndMetadataRoutes() {
        val bytes=encoded("<animation version='1' w='1' h='1'><frame id='a' src='frames/a.ora' duration-ms='40'/></animation>")
        val source=object:SourceResource {
            override val metadata=StreamMetadata(length=bytes.size.toLong(),name="opaque-resource")
            override fun open()=InputStreamSource(bytes.inputStream(),metadata)
        }
        val registry=CapabilityRegistry("canvas");registry.register(AnimationProjectHandler())
        for(operation in listOf(Operations.IDENTIFY,Operations.DECODE,Operations.THUMBNAIL,Operations.METADATA)) {
            assertTrue(operation,registry.probe(CapabilityResource(source.metadata,source=source),operation).any { it.descriptor.id=="canvas.animation-project" })
        }
    }
    @Test fun futureVersionsBadDelaysAndExternalEntitiesFailExplicitly() {
        for(xml in listOf("<animation version='2' w='1' h='1'/>","<animation version='1' w='1' h='1'><frame duration-ms='0'/></animation>","<!DOCTYPE animation [<!ENTITY x SYSTEM 'file:///not-readable'>]><animation version='1' w='1' h='1'/>")) {
            assertThrows(Exception::class.java) { metadata(encoded(xml)) }
        }
    }
}
