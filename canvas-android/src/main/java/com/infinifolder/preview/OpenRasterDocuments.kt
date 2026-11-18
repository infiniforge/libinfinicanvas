package com.infinifolder.preview

import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.*
import org.w3c.dom.*
import kotlin.math.roundToInt

/** Original encoded asset, optional visual decoder, and current representation. Buffers/bitmaps are host-owned. */
data class OpenRasterAsset(val reference:OpenRasterReference,val bytes:ByteArray,val palette:PaletteDocument?,
    val renderable:Boolean,val frame:BitmapFrame?,val rasterized:Boolean=false,val warnings:List<String> = emptyList()) {
    val extension:String get()=reference.path.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)
    val editablePalette:PaletteDocument? get()=palette.takeUnless { rasterized }
}
data class OpenRasterDocument(val document:LayerDocument,val container:OpenRasterPackage,
    val assets:Map<String,OpenRasterAsset>,val warnings:List<String> = emptyList())

/** Additive mixed-media authoring API; existing LayerDocument and ImageLayer constructors/copy ABI stay unchanged. */
object OpenRasterDocuments {
    private val modes=mapOf("normal" to PorterDuff.Mode.SRC_OVER,"multiply" to PorterDuff.Mode.MULTIPLY,
        "screen" to PorterDuff.Mode.SCREEN,"darken" to PorterDuff.Mode.DARKEN,"lighten" to PorterDuff.Mode.LIGHTEN)
    @JvmStatic fun decode(bytes:ByteArray):OpenRasterDocument? {
        val container=OpenRasterPackage.read(bytes) ?: return null
        PixelGeometry.validate(container.width,container.height)
        val assets=linkedMapOf<String,OpenRasterAsset>();val layers=container.references.map { ref ->
            val payload=container.entries[ref.path] ?: ByteArray(0)
            val decoded=decodeAsset(ref,payload);assets[ref.id]=decoded.second;decoded.first
        }
        // Empty containers remain useful editable canvases; unknown assets never need to be discarded.
        val all=layers.ifEmpty { listOf(ImageLayer("ora-canvas","Canvas",Bitmap.createBitmap(container.width,container.height,Bitmap.Config.ARGB_8888))) }
        return OpenRasterDocument(LayerDocument(container.width,container.height,all),container,assets,
            assets.values.flatMap { it.warnings })
    }
    @JvmStatic fun fromDocument(document:LayerDocument)=OpenRasterDocument(document,OpenRasterPackage.create(document.width,document.height),emptyMap())
    @JvmStatic @JvmOverloads fun decodeAsset(reference:OpenRasterReference,bytes:ByteArray,mimeType:String?=reference.mimeType):Pair<ImageLayer,OpenRasterAsset> {
        val warnings=mutableListOf<String>();val uri=Uri.parse("infinicanvas://ora/${reference.path}")
        val decoder=ThumbnailDecoder(null) { bytes.inputStream() }
        val frame=try { decoder.decodeEditorFrame(uri,reference.path.substringAfterLast('.',""),mimeType)
            ?: decoder.decodeEditorImage(uri,reference.path.substringAfterLast('.',""),mimeType)?.let { BitmapFrame(it) } }
            catch(cancel:InterruptedIOException) { throw cancel }
            catch(error:Exception) { warnings+="${reference.path}: ${error.message ?: "no visual decoder"}";null }
            catch(error:LinkageError) { warnings+="${reference.path}: optional image codec unavailable (${error.message})";null }
            catch(_:OutOfMemoryError) { warnings+="${reference.path}: not enough memory for a visual decoder";null }
        val bitmap=frame?.takeBitmap() ?: Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        if(frame==null) warnings+="${reference.path}: retained as a media layer; no image renderer is available"
        frame?.metadata?.warnings?.forEach { warnings+="${reference.path}: $it" }
        if(reference.blend !in modes) warnings+="${reference.path}: blend ${reference.blend} is preserved; preview uses normal blending"
        val palette=if(frame!=null) try { PaletteDocument.read(bytes) } catch(_:Exception) { null } catch(_:OutOfMemoryError) { warnings+="${reference.path}: source retained; not enough memory for palette editing";null } else null
        return ImageLayer(reference.id,reference.name,bitmap,reference.x,reference.y,reference.opacity,reference.visible,reference.blend) to
            OpenRasterAsset(reference,bytes,palette,frame!=null,frame,warnings=warnings.distinct())
    }
    @JvmStatic fun validate(project:OpenRasterDocument) {
        PixelGeometry.validate(project.document.width,project.document.height)
        require(project.document.layers.isNotEmpty() && project.document.layers.map { it.id }.distinct().size==project.document.layers.size)
        require(project.document.layers.all { it.opacity in 0..255 && it.blend.isNotBlank() })
    }
    @JvmStatic fun composite(project:OpenRasterDocument):Bitmap {
        validate(project);val doc=project.document
        return Bitmap.createBitmap(doc.width,doc.height,Bitmap.Config.ARGB_8888).also { draw(project,Canvas(it)) }
    }
    internal fun draw(project:OpenRasterDocument,canvas:Canvas,drawLayer:((ImageLayer,Canvas,Paint)->Unit)?=null) {
        val doc=project.document;val assets=project.assets;
        var group=0;var node=0
        fun groupDraw(e:Element,id:Int,root:Boolean=false) {
            if(e.getAttribute("visibility")=="hidden") return
            val opacity=e.getAttribute("opacity").ifBlank { "1" }.toFloat().coerceIn(0f,1f)
            val blend=OpenRasterPackage.blendNames[e.getAttribute("composite-op").ifBlank { "svg:src-over" }] ?: "normal"
            val needsLayer=opacity<1f || blend!="normal" || e.getAttribute("isolation")=="isolate"
            val saved=if(needsLayer) canvas.saveLayer(null,Paint().apply { alpha=(opacity*255).roundToInt();xfermode=PorterDuffXfermode(modes.getValue(blend)) }) else canvas.save()
            val children=mutableListOf<()->Unit>();val members=doc.layers.filter { (assets[it.id]?.reference?.parent ?: 0)==id }.iterator()
            fun leaf(layer:ImageLayer) { if(layer.visible && layer.opacity>0) {
                val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha=layer.opacity;xfermode=PorterDuffXfermode(modes[layer.blend] ?: PorterDuff.Mode.SRC_OVER) }
                if(drawLayer!=null) drawLayer(layer,canvas,paint) else canvas.drawBitmap(layer.bitmap,layer.x.toFloat(),layer.y.toFloat(),paint)
            } }
            for(child in OpenRasterPackage.children(e)) when {
                OpenRasterPackage.local(child)=="stack" -> {
                    val childId=++group
                    // Reserve descendants now so sibling group IDs do not depend on visibility/order of drawing.
                    fun reserve(n:Element) { OpenRasterPackage.children(n).forEach { if(OpenRasterPackage.local(it)=="stack") { group++;reserve(it) } else if(it.hasAttribute("src")) node++ } }
                    val startGroup=childId;val beforeNode=node;val beforeGroup=group;reserve(child)
                    children+={ val outerGroup=group;val outerNode=node;group=beforeGroup;node=beforeNode;groupDraw(child,startGroup);group=outerGroup;node=outerNode }
                }
                child.hasAttribute("src") -> { node++;if(members.hasNext()) { val layer=members.next();children+={ leaf(layer) } } }
            }
            while(members.hasNext()) { val layer=members.next();children+={ leaf(layer) } }
            children.asReversed().forEach { it() };canvas.restoreToCount(saved)
        }
        val root=OpenRasterPackage.children(project.container.xml.documentElement).first { OpenRasterPackage.local(it)=="stack" }
        groupDraw(root,0,true)
    }
    @JvmStatic fun write(project:OpenRasterDocument,output:OutputStream) {
        validate(project);val doc=project.document;val xml=OpenRasterPackage.builder().newDocument()
        val sourceXml=project.container.xml
        for(i in 0 until sourceXml.childNodes.length) xml.appendChild(xml.importNode(sourceXml.childNodes.item(i),true))
        xml.documentElement.setAttribute("w",doc.width.toString());xml.documentElement.setAttribute("h",doc.height.toString())
        val root=OpenRasterPackage.children(xml.documentElement).first { OpenRasterPackage.local(it)=="stack" }
        val groups=linkedMapOf(0 to root);val originals=mutableListOf<Element>();var group=0
        val origins=hashMapOf<Int,Pair<Int,Int>>()
        fun collect(e:Element,id:Int,px:Int,py:Int) {
            val x=Math.addExact(px,e.getAttribute("x").ifBlank { "0" }.toInt());val y=Math.addExact(py,e.getAttribute("y").ifBlank { "0" }.toInt());origins[id]=x to y
            OpenRasterPackage.children(e).forEach { if(OpenRasterPackage.local(it)=="stack") { val child=++group;groups[child]=it;collect(it,child,x,y) } else if(it.hasAttribute("src")) originals+=it }
        };collect(root,0,0,0)
        val entries=project.container.entries.toMutableMap();val desired=linkedMapOf<Int,MutableList<Element>>()
        val payloads=hashMapOf<String,ByteArray>()
        val liveIds=doc.layers.mapTo(hashSetOf()) { it.id }
        val livePaths=project.assets.filterKeys { it in liveIds }.values.mapTo(hashSetOf()) { it.reference.path }
        project.container.references.filter { it.id !in liveIds && it.path !in livePaths }.forEach { entries.remove(it.path) }
        project.assets.filterKeys { it in liveIds }.values.filter { !it.rasterized && entries[it.reference.path]?.contentEquals(it.bytes)==true }.forEach { payloads[it.reference.path]=it.bytes }
        for(layer in doc.layers) {
            OpenRasterPackage.interrupted();val asset=project.assets[layer.id];val ref=asset?.reference
            val node=ref?.node?.let { originals.getOrNull(it)?.cloneNode(true) as? Element } ?: xml.createElement("layer")
            if(asset?.rasterized==true && asset.extension!="png") entries.putIfAbsent(asset.reference.path,asset.bytes)
            val encoded=if(asset!=null && !asset.rasterized) asset.bytes else png(layer.bitmap)
            var path=if(asset!=null && !asset.rasterized) asset.reference.path else if(asset?.extension=="png") asset.reference.path else "data/${layer.id.replace(Regex("[^A-Za-z0-9_-]"),"_")}.png"
            val conflict=payloads[path]
            if(conflict!=null && !conflict.contentEquals(encoded)) {
                val ext=path.substringAfterLast('.',"bin");val stem=path.substringBeforeLast('.',path)
                var suffix=1;while("$stem-$suffix.$ext" in entries || "$stem-$suffix.$ext" in payloads) suffix++
                path="$stem-$suffix.$ext"
            } else if((asset?.rasterized==true && asset.extension!="png") || (asset==null && path in entries)) {
                var suffix=1;val stem=path.substringBeforeLast('.')
                while(path in entries || path in payloads) path="$stem-${suffix++}.png"
            }
            payloads[path]=encoded;entries[path]=encoded
            node.setAttribute("src",path);node.setAttribute("name",layer.name);node.setAttribute("x",Math.subtractExact(layer.x,origins[ref?.parent ?: 0]?.first ?: 0).toString());node.setAttribute("y",Math.subtractExact(layer.y,origins[ref?.parent ?: 0]?.second ?: 0).toString())
            node.setAttribute("opacity",(layer.opacity/255.0).toString());node.setAttribute("visibility",if(layer.visible)"visible" else "hidden")
            node.setAttribute("composite-op",OpenRasterPackage.blendNames.entries.firstOrNull { it.value==layer.blend }?.key ?: layer.blend)
            if(asset?.rasterized==true && asset.extension!="png") {
                var prefix="infini";while(xml.documentElement.lookupNamespaceURI(prefix)?.let { it!=OpenRasterPackage.NS }==true) prefix+="x"
                xml.documentElement.setAttributeNS("http://www.w3.org/2000/xmlns/","xmlns:$prefix",OpenRasterPackage.NS)
                node.setAttributeNS(OpenRasterPackage.NS,"$prefix:original-src",asset.reference.path)
            }
            desired.getOrPut(ref?.parent ?: 0) { mutableListOf() }+=node
        }
        // Preserve every group, unknown element/attribute and metadata node; replace only media slots.
        for((id,parent) in groups) {
            val replacement=(desired[id] ?: emptyList()).iterator();val children=(0 until parent.childNodes.length).map { parent.childNodes.item(it) }
            for(child in children) if(child is Element && child.hasAttribute("src") && OpenRasterPackage.local(child)!="stack") {
                if(replacement.hasNext()) parent.replaceChild(replacement.next(),child) else parent.removeChild(child)
            }
            while(replacement.hasNext()) parent.appendChild(replacement.next())
        }
        entries["stack.xml"]=OpenRasterPackage.xmlBytes(xml)
        val merged=composite(project);try { entries["mergedimage.png"]=png(merged) } finally { merged.recycle() }
        OpenRasterPackage.zip(entries,output)
    }
    private fun png(bitmap:Bitmap)=BitmapFrame(bitmap).use { ImageEncoding.png(it).use { encoded->encoded.takeBytes() } }
    @JvmStatic fun playback(project:OpenRasterDocument):Drawable? =
        if(project.assets.values.any { !it.rasterized && (it.frame?.animation!=null || it.frame?.colorCycling!=null || it.frame?.animationDrawable!=null) }) OpenRasterPlayback(project) else null
}
