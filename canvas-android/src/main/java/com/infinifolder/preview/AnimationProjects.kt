package com.infinifolder.preview

import java.io.*
import java.util.zip.*

/** Timeline frames contain independent ORA documents, never entries in the image layer stack. */
data class AnimationProjectFrame(val id:String,val name:String,val document:OpenRasterDocument,val delayMillis:Int)
data class AnimationProject(val width:Int,val height:Int,val frames:List<AnimationProjectFrame>,val plays:Int=0,
    val attachments:Map<String,ByteArray> = emptyMap())

/** Version 1: ZIP, animation.xml, and one ordinary/extensible ORA file per timeline frame. */
object AnimationProjects {
    const val EXTENSION="icanim"
    const val MIME_TYPE="application/x-infinicanvas-animation"
    @JvmStatic fun validate(project:AnimationProject) {
        PixelGeometry.validate(project.width,project.height)
        require(project.plays>=0 && project.frames.isNotEmpty())
        require(project.frames.map { it.id }.distinct().size==project.frames.size)
        project.frames.forEach {
            require(it.id.isNotBlank() && it.delayMillis in 1..65535000)
            require(it.document.document.width==project.width && it.document.document.height==project.height) { "Frame canvas differs from timeline canvas" }
            OpenRasterDocuments.validate(it.document)
        }
    }
    @JvmStatic fun fromAnimation(animation:AnimationDocument):AnimationProject {
        AnimationDocuments.validate(animation)
        return AnimationProject(animation.width,animation.height,animation.frames.mapIndexed { i,frame ->
            AnimationProjectFrame("frame-$i","Frame ${i+1}",OpenRasterDocuments.fromDocument(LayerDocument(animation.width,animation.height,
                listOf(ImageLayer("frame-$i-pixels","Pixels",frame.bitmap)))),frame.delayMillis)
        },animation.plays)
    }
    /** Full composites for playback/APNG export; frame layer documents remain intact. */
    @JvmStatic fun flatten(project:AnimationProject):AnimationDocument {
        validate(project);val frames=mutableListOf<AnimationFrame>()
        try { project.frames.forEach { OpenRasterPackage.interrupted();frames+=AnimationFrame(OpenRasterDocuments.composite(it.document),it.delayMillis) }
            return AnimationDocument(project.width,project.height,frames,project.plays)
        } catch(error:Throwable) { frames.forEach { it.bitmap.recycle() };throw error }
    }
    @JvmStatic fun decode(bytes:ByteArray):AnimationProject? {
        if(bytes.size<4 || bytes[0]!=80.toByte() || bytes[1]!=75.toByte()) return null
        val entries=readEntries(bytes)
        val timeline=entries["animation.xml"] ?: return null
        require(!Regex("<!\\s*DOCTYPE",RegexOption.IGNORE_CASE).containsMatchIn(timeline.toString(Charsets.UTF_8).replace("\u0000","")))
        val root=OpenRasterPackage.builder().parse(timeline.inputStream()).documentElement
        require(OpenRasterPackage.local(root)=="animation" && root.getAttribute("version")=="1") { "Unsupported animation project version" }
        val width=root.getAttribute("w").toInt();val height=root.getAttribute("h").toInt()
        val used=hashSetOf("mimetype","animation.xml")
        val frames=OpenRasterPackage.children(root).filter { OpenRasterPackage.local(it)=="frame" }.map { e ->
            val path=OpenRasterPackage.path(e.getAttribute("src"));used+=path
            val document=OpenRasterDocuments.decode(entries[path] ?: error("Missing animation frame: $path")) ?: error("Frame is not ORA: $path")
            AnimationProjectFrame(e.getAttribute("id"),e.getAttribute("name"),document,e.getAttribute("duration-ms").toInt())
        }
        return AnimationProject(width,height,frames,root.getAttribute("plays").ifBlank { "0" }.toInt(),entries.filterKeys { it !in used }).also(::validate)
    }
    internal fun readEntries(bytes:ByteArray):Map<String,ByteArray> {
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip -> while(true) {
            OpenRasterPackage.interrupted();val entry=zip.nextEntry ?: break;val name=OpenRasterPackage.path(entry.name)
            require(name !in entries) { "Duplicate animation entry" }
            val out=ByteArrayOutputStream();val buffer=ByteArray(65536)
            while(true) { OpenRasterPackage.interrupted();val n=zip.read(buffer);if(n<0) break;require(n>0);out.write(buffer,0,n) }
            entries[name]=out.toByteArray();zip.closeEntry()
        } }
        return entries
    }
    @JvmStatic fun write(project:AnimationProject,output:OutputStream) {
        validate(project);val xml=OpenRasterPackage.builder().newDocument();val root=xml.createElement("animation");xml.appendChild(root)
        root.setAttribute("version","1");root.setAttribute("w",project.width.toString());root.setAttribute("h",project.height.toString());root.setAttribute("plays",project.plays.toString())
        val entries=project.attachments.toMutableMap()
        project.frames.forEachIndexed { i,frame ->
            OpenRasterPackage.interrupted();var path="frames/$i.ora";var suffix=0
            while(path in entries) path="frames/$i-${++suffix}.ora"
            entries[path]=ByteArrayOutputStream().also { OpenRasterDocuments.write(frame.document,it) }.toByteArray()
            root.appendChild(xml.createElement("frame").apply { setAttribute("id",frame.id);setAttribute("name",frame.name);setAttribute("src",path);setAttribute("duration-ms",frame.delayMillis.toString()) })
        }
        entries["animation.xml"]=OpenRasterPackage.xmlBytes(xml)
        ZipOutputStream(object:FilterOutputStream(output) { override fun close() { flush() } }).use { zip ->
            val mime=MIME_TYPE.toByteArray(Charsets.US_ASCII);zip.putNextEntry(ZipEntry("mimetype").apply { method=ZipEntry.STORED;size=mime.size.toLong();compressedSize=size;crc=CRC32().apply { update(mime) }.value });zip.write(mime);zip.closeEntry()
            zip.putNextEntry(ZipEntry("animation.xml"));zip.write(entries.getValue("animation.xml"));zip.closeEntry()
            entries.forEach { (path,data) -> if(path!="mimetype" && path!="animation.xml") { OpenRasterPackage.interrupted();zip.putNextEntry(ZipEntry(OpenRasterPackage.path(path)));zip.write(data);zip.closeEntry() } }
        }
    }
}
