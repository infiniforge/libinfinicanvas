package com.infinifolder.preview

import android.graphics.Bitmap
import io.github.gretchycat.infinistream.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Discoverable still/animation route shared by hosts; no editor or application dependency. */
class AnimationProjectHandler:ByteImageCapabilityHandler,MetadataCapabilityHandler {
    override val descriptor=HandlerDescriptor("canvas.animation-project","canvas","InfiniCanvas animation project",
        listOf(FormatSupport(setOf(AnimationProjects.MIME_TYPE),setOf(AnimationProjects.EXTENSION))),
        setOf(Operations.IDENTIFY,Operations.DECODE,Operations.THUMBNAIL,Operations.METADATA),
        metadata=setOf("width","height","frameCount","plays","durationMillis"),
        limitations=listOf("ZIP/XML timeline version 1, ORA frames; in-memory Android bitmap composition"),priority=20)
    override fun decode(bytes:ByteArray,targetPixels:Int,extension:String)=frame(bytes,targetPixels)?.takeBitmap()
    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        if(context.resource.source==null) return super<ByteImageCapabilityHandler>.probe(context)
        val identity=ImageSignatures.identify(context.prefix())
        return if(identity?.mimeType==AnimationProjects.MIME_TYPE) HandlerProbe(MatchConfidence.VERIFIED,descriptor.operations,identity.evidence,
            mapOf("mimeType" to identity.mimeType)) else HandlerProbe(MatchConfidence.NONE,emptySet())
    }
    override fun extractMetadata(context:MetadataReadContext):MetadataRecord? {
        // Metadata extraction is deliberately bounded independently from editor document opening.
        val prefix=context.readAt(0,minOf(context.length ?: 1048576,1048576).toInt())
        ZipInputStream(prefix.inputStream()).use { zip ->
            repeat(16) {
                context.cancellation.check();val entry=zip.nextEntry ?: return null
                if(entry.name=="animation.xml") {
                    val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(out.size()<=524288) { context.cancellation.check();val n=zip.read(buffer);if(n<0) break;require(n>0);out.write(buffer,0,n) }
                    require(out.size()<=524288) { "Timeline exceeds metadata read budget" }
                    val bytes=out.toByteArray();require(!bytes.toString(Charsets.UTF_8).replace("\u0000","").contains("<!DOCTYPE",true))
                    val root=OpenRasterPackage.builder().parse(bytes.inputStream()).documentElement
                    require(OpenRasterPackage.local(root)=="animation" && root.getAttribute("version")=="1")
                    val frames=OpenRasterPackage.children(root).filter { OpenRasterPackage.local(it)=="frame" }
                    val w=root.getAttribute("w").toInt();val h=root.getAttribute("h").toInt();require(w>0 && h>0)
                    val plays=root.getAttribute("plays").ifBlank { "0" }.toInt();require(plays>=0)
                    var duration=0L;frames.forEach { val delay=it.getAttribute("duration-ms").toInt();require(delay in 1..65535000);duration=Math.addExact(duration,delay.toLong()) }
                    return MetadataRecord("icanim",AnimationProjects.MIME_TYPE,"InfiniCanvas Animation",mapOf("width" to "$w","height" to "$h","frameCount" to "${frames.size}","plays" to "$plays","durationMillis" to "$duration"))
                }
                // Our writer puts the timeline immediately after mimetype. Do not decompress media for metadata.
                if(entry.name!="mimetype") return null
                zip.closeEntry()
            }
        };return null
    }
    companion object {
        @JvmStatic fun frame(bytes:ByteArray,targetPixels:Int):BitmapFrame? {
            require(targetPixels>0)
            if(ImageSignatures.extension(bytes)!="icanim") return null
            val entries=AnimationProjects.readEntries(bytes);val timeline=entries["animation.xml"] ?: return null
            require(!timeline.toString(Charsets.UTF_8).replace("\u0000","").contains("<!DOCTYPE",true))
            val root=OpenRasterPackage.builder().parse(timeline.inputStream()).documentElement
            require(OpenRasterPackage.local(root)=="animation" && root.getAttribute("version")=="1")
            val sourceW=root.getAttribute("w").toInt();val sourceH=root.getAttribute("h").toInt();require(sourceW>0 && sourceH>0)
            val factor=minOf(1.0,targetPixels.toDouble()/maxOf(sourceW,sourceH));val w=maxOf(1,(sourceW*factor).toInt());val h=maxOf(1,(sourceH*factor).toInt());PixelGeometry.validate(w,h)
            val frames=OpenRasterPackage.children(root).filter { OpenRasterPackage.local(it)=="frame" };require(frames.isNotEmpty())
            val paths=frames.map { OpenRasterPackage.path(it.getAttribute("src")) };require(paths.all { it in entries })
            val ends=LongArray(frames.size);var total=0L
            frames.forEachIndexed { i,e -> val delay=e.getAttribute("duration-ms").toInt();require(delay in 1..65535000);total=Math.addExact(total,delay*1_000_000L);ends[i]=total }
            val plays=root.getAttribute("plays").ifBlank { "0" }.toInt();require(plays>=0)
            fun bitmap(index:Int):Bitmap {
                OpenRasterPackage.interrupted();val encoded=entries.getValue(paths[index])
                val preview=LayeredImageDecoder.decodeThumbnail(encoded.inputStream(),targetPixels)
                    ?: OpenRasterDocuments.decode(encoded)?.let(OpenRasterDocuments::composite) ?: error("No ORA frame renderer")
                if(preview.width==w && preview.height==h) return preview
                return Bitmap.createScaledBitmap(preview,w,h,true).also { if(it!==preview) preview.recycle() }
            }
            val first=bitmap(0)
            val player=object:BitmapAnimation {
                override val width=w;override val height=h;override val description="${frames.size} ORA animation frames"
                override val durationNanos=total
                private var current=0;private var image=first
                override fun render(elapsedNanos:Long,output:IntArray,force:Boolean):Boolean {
                    val elapsed=elapsedNanos.coerceAtLeast(0);val time=if(plays>0 && elapsed/total>=plays) total-1 else elapsed%total
                    var low=0;var high=ends.lastIndex
                    while(low<high) { val mid=(low+high) ushr 1;if(time<ends[mid]) high=mid else low=mid+1 }
                    if(low==current && !force) return false
                    if(low!=current) { val next=if(low==0) first else bitmap(low);if(image!==first) image.recycle();image=next;current=low }
                    require(output.size>=w*h);image.getPixels(output,0,w,0,0,w,h);return true
                }
            }
            return BitmapFrame(first).also { it.animation=player;it.animationDurationNanos=total }
        }
    }
}
