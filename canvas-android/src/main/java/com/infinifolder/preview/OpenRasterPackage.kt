package com.infinifolder.preview

import java.io.*
import java.util.zip.*
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.*

/** An ORA reference can name any encoded file. Positions are in document coordinates. */
data class OpenRasterReference(val id:String,val path:String,val name:String,val x:Int,val y:Int,
    val opacity:Int,val visible:Boolean,val blend:String,val parent:Int,val parentX:Int,val parentY:Int,
    val node:Int?,val mimeType:String?=null)

/** Lossless entry payloads and extension-aware XML. Does not require an image renderer. */
class OpenRasterPackage private constructor(val width:Int,val height:Int,val entries:Map<String,ByteArray>,
    val references:List<OpenRasterReference>,internal val xml:Document) {
    companion object {
        internal val blendNames=mapOf("svg:src-over" to "normal","svg:multiply" to "multiply","svg:screen" to "screen","svg:darken" to "darken","svg:lighten" to "lighten")
        internal const val NS="urn:infiniforge:ora:1"
        internal fun local(e:Element)=e.localName ?: e.tagName.substringAfter(':')
        internal fun children(e:Element):List<Element> = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
        internal fun path(name:String):String {
            val normalized=name.replace('\\','/').split('/').filter { it!="." }.joinToString("/")
            require(normalized.isNotBlank() && !normalized.startsWith('/') && '\u0000' !in normalized && normalized.split('/').none { it==".." }) { "Unsafe ORA entry path" }
            return normalized
        }
        internal fun builder()=DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware=true;isExpandEntityReferences=false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities",false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities",false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema","") }
        }.newDocumentBuilder().apply { setEntityResolver { _,_->throw IOException("External ORA entities are not allowed") } }
        @JvmStatic fun read(bytes:ByteArray):OpenRasterPackage? {
            if(bytes.size<4 || bytes[0]!=80.toByte() || bytes[1]!=75.toByte()) return null
            val entries=linkedMapOf<String,ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while(true) {
                    interrupted();val entry=zip.nextEntry ?: break;val name=path(entry.name)
                    require(name !in entries) { "Duplicate ORA entry: $name" }
                    val out=ByteArrayOutputStream();val buffer=ByteArray(65536)
                    while(true) { interrupted();val n=zip.read(buffer);if(n<0) break;require(n>0) { "ORA stream made no progress" };out.write(buffer,0,n) }
                    entries[name]=out.toByteArray();zip.closeEntry()
                }
            }
            val stack=entries["stack.xml"] ?: return null
            val xmlText=stack.toString(Charsets.UTF_8).replace("\u0000","")
            require(!Regex("<!\\s*DOCTYPE",RegexOption.IGNORE_CASE).containsMatchIn(xmlText)) { "ORA document types are not allowed" }
            val xml=builder().parse(stack.inputStream());val image=xml.documentElement
            if(local(image)!="image") return null
            val width=image.getAttribute("w").toInt();val height=image.getAttribute("h").toInt()
            require(width>0 && height>0) { "Invalid ORA canvas dimensions" }
            val refs=mutableListOf<OpenRasterReference>();var group=0;var node=0
            fun number(e:Element,name:String)=e.getAttribute(name).takeIf { it.isNotEmpty() }?.toInt() ?: 0
            fun walk(parent:Element,parentId:Int,px:Int,py:Int) {
                for(e in children(parent)) when {
                    local(e)=="stack" -> { val id=++group;walk(e,id,Math.addExact(px,number(e,"x")),Math.addExact(py,number(e,"y"))) }
                    e.hasAttribute("src") -> {
                        val source=path(e.getAttribute("src"));val opacity=e.getAttribute("opacity").ifBlank { "1" }.toDouble()
                        require(opacity.isFinite() && opacity in 0.0..1.0) { "Invalid ORA opacity" }
                        val rawBlend=e.getAttribute("composite-op").ifBlank { "svg:src-over" }
                        refs+=OpenRasterReference("ora-layer-${node}",source,e.getAttribute("name").ifBlank { source.substringAfterLast('/') },
                            Math.addExact(px,number(e,"x")),Math.addExact(py,number(e,"y")),kotlin.math.round(opacity*255).toInt(),e.getAttribute("visibility")!="hidden",
                            blendNames[rawBlend] ?: rawBlend,parentId,px,py,node++,e.getAttribute("mime-type").ifBlank { e.getAttribute("type") }.takeIf { '/' in it })
                    }
                }
            }
            val root=children(image).firstOrNull { local(it)=="stack" } ?: return null
            walk(root,0,number(root,"x"),number(root,"y"))
            // Each additional payload gets a layer too. Generated previews and control files are not source media.
            val used=refs.mapTo(hashSetOf()) { it.path }
            entries.keys.filter { !it.endsWith('/') && it !in used && !control(it) }.forEach { source ->
                refs+=OpenRasterReference("ora-asset-${refs.size}",source,source.substringAfterLast('/'),0,0,255,false,"normal",0,0,0,null)
            }
            return OpenRasterPackage(width,height,entries.toMap(),refs.toList(),xml)
        }
        @JvmStatic fun create(width:Int,height:Int):OpenRasterPackage {
            require(width>0 && height>0);val xml=builder().newDocument();val image=xml.createElement("image")
            image.setAttribute("version","0.0.3");image.setAttribute("w",width.toString());image.setAttribute("h",height.toString())
            xml.appendChild(image);image.appendChild(xml.createElement("stack"))
            return OpenRasterPackage(width,height,emptyMap(),emptyList(),xml)
        }
        private fun control(name:String)=name in setOf("mimetype","stack.xml","mergedimage.png") || name.startsWith("Thumbnails/",true)
        internal fun interrupted() { if(Thread.currentThread().isInterrupted) throw InterruptedIOException() }
        internal fun xmlBytes(xml:Document):ByteArray {
            val factory=TransformerFactory.newInstance()
            runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","") };runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet","") }
            return ByteArrayOutputStream().also { out -> factory.newTransformer().apply { setOutputProperty(OutputKeys.ENCODING,"UTF-8") }.transform(DOMSource(xml),StreamResult(out)) }.toByteArray()
        }
        internal fun zip(entries:Map<String,ByteArray>,output:OutputStream) {
            // Finish the ZIP without taking ownership of the destination.
            val zip=ZipOutputStream(object:FilterOutputStream(output) { override fun close() { flush() } })
            zip.use {
                fun entry(name:String,data:ByteArray,stored:Boolean=false) {
                    interrupted();val e=ZipEntry(path(name));if(stored) { e.method=ZipEntry.STORED;e.size=data.size.toLong();e.compressedSize=e.size;e.crc=CRC32().apply { update(data) }.value }
                    zip.putNextEntry(e);zip.write(data);zip.closeEntry()
                }
                entry("mimetype","image/openraster".toByteArray(Charsets.US_ASCII),true)
                entries.forEach { (name,data)->if(name!="mimetype") entry(name,data) }
            }
        }
    }
    /** Pure package round trip: opaque payloads and original stack.xml bytes are unchanged. */
    fun write(output:OutputStream)=zip(entries,output)
}
