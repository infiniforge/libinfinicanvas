package com.infinifolder.preview

import android.graphics.*
import java.io.*
import java.util.zip.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** Topmost layer first. Host owns all returned bitmaps and document policy. */
data class ImageLayer(val id: String, val name: String, val bitmap: Bitmap, val x: Int = 0, val y: Int = 0,
    val opacity: Int = 255, val visible: Boolean = true, val blend: String = "normal")
data class LayerDocument(val width: Int, val height: Int, val layers: List<ImageLayer>)

/** Bounded authoring adapters. Unsupported semantics fail explicitly rather than silently flattening. */
object LayerDocuments {
    const val MAX_LAYERS=Int.MAX_VALUE
    private const val MAX_PIXELS=Long.MAX_VALUE
    private val modes=mapOf("normal" to PorterDuff.Mode.SRC_OVER,"multiply" to PorterDuff.Mode.MULTIPLY,
        "screen" to PorterDuff.Mode.SCREEN,"darken" to PorterDuff.Mode.DARKEN,"lighten" to PorterDuff.Mode.LIGHTEN)
    private val psdModes=mapOf("norm" to "normal","mul " to "multiply","scrn" to "screen","dark" to "darken","lite" to "lighten")
    private val oraModes=mapOf("svg:src-over" to "normal","svg:multiply" to "multiply","svg:screen" to "screen","svg:darken" to "darken","svg:lighten" to "lighten")

    fun validate(doc:LayerDocument) {
        PixelGeometry.validate(doc.width,doc.height)
        require(doc.layers.size in 1..MAX_LAYERS && doc.layers.map { it.id }.distinct().size==doc.layers.size)
        require(doc.layers.sumOf { it.bitmap.width.toLong()*it.bitmap.height }<=MAX_PIXELS) { "Layer pixel budget exceeded" }
        require(doc.layers.all { it.opacity in 0..255 && it.blend in modes })
    }
    fun composite(doc:LayerDocument):Bitmap {
        validate(doc)
        val bitmap=Bitmap.createBitmap(doc.width,doc.height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        doc.layers.asReversed().forEach { layer -> if(layer.visible && layer.opacity>0) {
            paint.alpha=layer.opacity
            paint.xfermode=PorterDuffXfermode(modes.getValue(layer.blend))
            canvas.drawBitmap(layer.bitmap,layer.x.toFloat(),layer.y.toFloat(),paint)
        } }
        return bitmap
    }
    fun decode(bytes:ByteArray, targetPixels:Int=2048):LayerDocument? {
        require(targetPixels>0)
        val signature=bytes.take(9).toByteArray().toString(Charsets.US_ASCII)
        return when {
            signature.startsWith("8BPS") -> psd(bytes,targetPixels)
            signature.startsWith("gimp xcf ") -> xcf(bytes,targetPixels)
            bytes.size>=4 && bytes[0]==80.toByte() && bytes[1]==75.toByte() -> ora(bytes,targetPixels)
            else -> null
        }
    }
    private fun scale(width:Int,height:Int,target:Int):Double {
        PixelGeometry.validate(width,height)
        return minOf(1.0,target.toDouble()/maxOf(width,height))
    }
    private fun pixels(width:Int,height:Int) {
        PixelGeometry.validate(width,height)
    }
    private fun image(data:IntArray,w:Int,h:Int,factor:Double):Bitmap {
        val sw=(w*factor).roundToInt().coerceAtLeast(1); val sh=(h*factor).roundToInt().coerceAtLeast(1)
        val sampled=if(sw==w && sh==h) data else IntArray(sw*sh) { i -> data[(i/sw).toLong().times(h).div(sh).toInt()*w+(i%sw).toLong().times(w).div(sw).toInt()] }
        return Bitmap.createBitmap(sampled,sw,sh,Bitmap.Config.ARGB_8888)
    }
    private fun finish(w:Int,h:Int,factor:Double,layers:List<ImageLayer>):LayerDocument =
        LayerDocument((w*factor).roundToInt().coerceAtLeast(1),(h*factor).roundToInt().coerceAtLeast(1),layers).also(::validate)

    private fun ora(bytes:ByteArray,target:Int):LayerDocument {
        val entries=OpenRasterArchive.readEntries(bytes,true,unrestricted=true) ?: error("Invalid or oversized OpenRaster archive")
        val xml=entries.getValue("stack.xml")
        require(Regex("<stack(?:\\s|>)").findAll(xml.toString(Charsets.UTF_8)).count()==1) { "Nested OpenRaster groups are not yet editable" }
        val doc=OpenRasterArchive.parseStack(xml,unrestricted=true) ?: error("Invalid OpenRaster stack")
        require(doc.layers.size in 1..MAX_LAYERS)
        val factor=scale(doc.width,doc.height,target); var total=0L
        val layers=doc.layers.mapIndexed { i,layer ->
            val blend=oraModes[layer.composite] ?: error("Unsupported OpenRaster blend: ${layer.composite}")
            val encoded=entries[layer.src] ?: error("Missing layer ${layer.src}")
            require(encoded.size>=8 && encoded[0]==(-119).toByte() && encoded[1]==80.toByte()) { "Editable ORA layers must be PNG" }
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(encoded,0,encoded.size,options)
            pixels(options.outWidth,options.outHeight); total+=options.outWidth.toLong()*options.outHeight
            require(total<=MAX_PIXELS) { "Source layer pixel budget exceeded" }
            val sw=(options.outWidth*factor).roundToInt().coerceAtLeast(1)
            val sh=(options.outHeight*factor).roundToInt().coerceAtLeast(1)
            options.inJustDecodeBounds=false; options.inSampleSize=1
            while(options.outWidth/(options.inSampleSize*2)>=sw && options.outHeight/(options.inSampleSize*2)>=sh) options.inSampleSize*=2
            val decoded=BitmapFactory.decodeByteArray(encoded,0,encoded.size,options) ?: error("Invalid layer PNG")
            val bitmap=if(decoded.width==sw && decoded.height==sh) decoded else Bitmap.createScaledBitmap(decoded,sw,sh,true).also { if(it!==decoded) decoded.recycle() }
            ImageLayer("layer-$i",layer.name,bitmap,(layer.x*factor).roundToInt(),(layer.y*factor).roundToInt(),(layer.opacity*255).roundToInt(),layer.visible,blend)
        }
        return finish(doc.width,doc.height,factor,layers)
    }

    private data class PsdRecord(val name:String,val w:Int,val h:Int,val x:Int,val y:Int,val opacity:Int,val visible:Boolean,
        val blend:String,val channels:List<Pair<Int,Int>>)
    private fun psd(bytes:ByteArray,target:Int):LayerDocument {
        val r=Reader(bytes)
        require(r.text(4)=="8BPS" && r.u16()==1) { "Only PSD version 1 is editable" }
        r.skip(6); val channels=r.u16(); val h=r.i32(); val w=r.i32(); val depth=r.u16(); val mode=r.u16()
        val factor=scale(w,h,target)
        require(channels in 1..56 && depth==8 && mode in setOf(1,3)) { "Editable PSD requires 8-bit RGB or grayscale" }
        r.skip(r.length()); r.skip(r.length())
        val maskEnd=r.sectionEnd(); val infoEnd=r.sectionEnd()
        require(infoEnd<=maskEnd && infoEnd>r.at+2)
        val count=abs(r.i16()); require(count in 1..MAX_LAYERS)
        var total=0L
        val records=List(count) { index ->
            val top=r.i32(); val left=r.i32(); val bottom=r.i32(); val right=r.i32()
            val lw=right.toLong()-left; val lh=bottom.toLong()-top
            require(lw in 1..Int.MAX_VALUE.toLong() && lh in 1..Int.MAX_VALUE.toLong()) { "Empty/group PSD layers are not editable" }
            pixels(lw.toInt(),lh.toInt()); total+=lw*lh; require(total<=MAX_PIXELS)
            val n=r.u16(); require(n in 1..8)
            val channelInfo=List(n) { r.i16() to r.length() }
            require(channelInfo.all { it.first in -1..(if(mode==3) 2 else 0) }) { "PSD layer masks are not supported" }
            require(r.text(4)=="8BIM")
            val blend=psdModes[r.text(4)] ?: error("Unsupported PSD blend mode")
            val opacity=r.u8(); require(r.u8()==0) { "PSD clipping layers are not supported" }
            val flags=r.u8(); r.skip(1)
            val extraEnd=r.sectionEnd()
            require(extraEnd<=infoEnd)
            val maskSize=r.length(); require(maskSize==0) { "PSD masks are not supported" }
            val blendRanges=r.bytes(r.length())
            require(blendRanges.indices.all { (blendRanges[it].toInt() and 255)==if(it%4<2) 0 else 255 }) { "Custom PSD blend ranges are not supported" }
            val pascalStart=r.at; val nameLength=r.u8()
            var name=r.text(nameLength,Charsets.ISO_8859_1)
            r.at=pascalStart+((nameLength+1+3)/4)*4
            while(r.at+12<=extraEnd) {
                require(r.text(4) in setOf("8BIM","8B64"))
                val key=r.text(4); val size=r.length(); val end=r.at+size
                require(end<=extraEnd)
                when(key) {
                    "luni" -> { val length=r.i32(); require(length in 0..32768 && length.toLong()*2<=end-r.at); name=r.text(length*2,Charsets.UTF_16BE) }
                    "lyid","lclr","lspf","fxrp","lnsr","shmd" -> Unit
                    else -> error("PSD feature '$key' is not editable yet")
                }
                r.at=end+(size and 1)
            }
            r.at=extraEnd
            PsdRecord(name.ifBlank { "Layer ${index+1}" },lw.toInt(),lh.toInt(),left,top,opacity,flags and 2==0,blend,channelInfo)
        }
        val layers=records.mapIndexed { index,record ->
            val planes=mutableMapOf<Int,ByteArray>()
            for((id,size) in record.channels) {
                val end=r.at+size; require(size>=2 && end<=infoEnd)
                val compression=r.u16(); val expected=record.w*record.h
                val data=when(compression) {
                    0 -> { require(end-r.at>=expected); r.bytes(expected) }
                    1 -> {
                        val lengths=IntArray(record.h) { r.u16() }; val output=ByteArray(expected)
                        for(y in 0 until record.h) {
                            require(lengths[y]<=end-r.at)
                            val row=ImageDecodeUtils.decodePackBits(bytes,r.at,record.w,r.at+lengths[y]).first
                            row.copyInto(output,y*record.w); r.skip(lengths[y])
                        }; output
                    }
                    2,3 -> {
                        val data=inflate(r.bytes(end-r.at),expected)
                        if(compression==3) for(y in 0 until record.h) for(x in 1 until record.w) {
                            val i=y*record.w+x; data[i]=(data[i].toInt()+data[i-1].toInt()).toByte()
                        }; data
                    }
                    else -> error("Unsupported PSD channel compression")
                }
                require(planes.put(id,data)==null); r.at=end
            }
            require(planes.containsKey(0) && (mode!=3 || planes.keys.containsAll(listOf(1,2)))) { "Missing PSD color channels" }
            val rgba=IntArray(record.w*record.h) { p ->
                fun sample(id:Int)=planes[id]?.get(p)?.toInt()?.and(255) ?: 255
                (sample(-1) shl 24) or (sample(0) shl 16) or (sample(if(mode==1) 0 else 1) shl 8) or sample(if(mode==1) 0 else 2)
            }
            ImageLayer("layer-$index",record.name,image(rgba,record.w,record.h,factor),
                (record.x*factor).roundToInt(),(record.y*factor).roundToInt(),record.opacity,record.visible,record.blend)
        }
        return finish(w,h,factor,layers)
    }

    private fun xcf(bytes:ByteArray,target:Int):LayerDocument {
        val r=Reader(bytes); require(r.text(9)=="gimp xcf ")
        val versionText=r.text(4); require(r.u8()==0)
        val version=if(versionText=="file") 0 else versionText.removePrefix("v").toIntOrNull() ?: error("Unknown XCF version")
        require(version in 0..23)
        val pointerSize=if(version>=11) 8 else 4
        fun pointer(reader:Reader):Int { if(pointerSize==8) require(reader.i32()==0) { "XCF offset exceeds input limit" }; return reader.length() }
        val w=r.i32(); val h=r.i32(); val base=r.i32(); val factor=scale(w,h,target)
        if(version>=4) require(r.i32() in (if(version==4) setOf(0) else setOf(150,175))) { "Only 8-bit XCF is editable" }
        var compression=0; var palette=IntArray(0)
        properties(r) { type,end -> when(type) {
            17 -> compression=r.u8()
            1 -> { val n=r.i32(); require(n in 1..256 && n*3<=end-r.at); palette=IntArray(n) { 0xff000000.toInt() or (r.u8() shl 16) or (r.u8() shl 8) or r.u8() } }
        } }
        require(base in 0..2 && compression in 0..2)
        val offsets=mutableListOf<Int>()
        while(true) { val p=pointer(r); if(p==0) break; offsets+=p; require(offsets.size<=MAX_LAYERS) }
        require(offsets.isNotEmpty()); var total=0L
        val layers=offsets.mapIndexed { index,offset ->
            val q=Reader(bytes,offset); val lw=q.i32(); val lh=q.i32(); val type=q.i32(); val name=q.text(q.length(),Charsets.UTF_8).trimEnd('\u0000')
            pixels(lw,lh); total+=lw.toLong()*lh; require(total<=MAX_PIXELS && type in 0..5)
            var opacity=255; var visible=true; var x=0; var y=0; var blend=0
            properties(q) { prop,end -> when(prop) {
                6 -> opacity=q.i32().coerceIn(0,255)
                33 -> opacity=(java.lang.Float.intBitsToFloat(q.i32())*255).roundToInt().coerceIn(0,255)
                8 -> visible=q.i32()!=0
                7 -> blend=q.i32()
                15 -> { x=q.i32(); y=q.i32() }
                29,30 -> error("XCF groups are not editable yet")
                35 -> require(abs(q.i32()) in setOf(0,1)) { "Unsupported XCF composite mode" }
                36,37 -> require(abs(q.i32()) in setOf(0,2,4)) { "Linear/Lab XCF blending is not supported" }
            }; require(q.at<=end) }
            require(blend in setOf(0,28)) { "Only normal XCF layers are editable" }
            val hierarchy=pointer(q); val mask=pointer(q)
            require(mask==0) { "XCF masks are not editable yet" }
            if(version>=20) require(pointer(q)==0) { "XCF layer effects are not supported" }
            val hr=Reader(bytes,hierarchy); require(hr.i32()==lw && hr.i32()==lh)
            val bpp=hr.i32(); val expectedBpp=when(type) {0->3;1->4;2,4->1;else->2}
            require(bpp==expectedBpp)
            val level=Reader(bytes,pointer(hr)); require(level.i32()==lw && level.i32()==lh)
            val rgba=IntArray(lw*lh)
            for(ty in 0 until (lh+63)/64) for(tx in 0 until (lw+63)/64) {
                val tileAt=pointer(level); val tw=minOf(64,lw-tx*64); val th=minOf(64,lh-ty*64)
                val data=ByteArray(tw*th*bpp)
                val ok=when(compression) {
                    0 -> { Reader(bytes,tileAt).bytes(data.size).copyInto(data); true }
                    1 -> LayeredImageDecoder.decodeXcfRleTile(bytes,tileAt,tw*th,bpp,data)
                    else -> LayeredImageDecoder.decodeXcfZlibTile(bytes,tileAt,data)
                }; require(ok) { "Malformed XCF tile" }
                for(py in 0 until th) for(px in 0 until tw) {
                    val p=(py*tw+px)*bpp
                    fun sample(c:Int)=data[p+c].toInt() and 255
                    val color=when(type) {
                        0,1 -> (sample(0) shl 16) or (sample(1) shl 8) or sample(2)
                        2,3 -> sample(0)*0x010101
                        else -> { val idx=sample(0); require(idx<palette.size); palette[idx] and 0xffffff }
                    }
                    val alpha=if(type in setOf(1,3,5)) sample(bpp-1) else 255
                    rgba[(ty*64+py)*lw+tx*64+px]=(alpha shl 24) or color
                }
            }
            ImageLayer("layer-$index",name,image(rgba,lw,lh,factor),(x*factor).roundToInt(),(y*factor).roundToInt(),opacity,visible)
        }
        return finish(w,h,factor,layers)
    }
    private fun properties(r:Reader,read:(Int,Int)->Unit) {
        repeat(4096) {
            val type=r.i32(); val end=r.sectionEnd()
            if(type==0) { require(end==r.at); return }
            read(type,end); require(r.at<=end); r.at=end
        }; error("Too many XCF properties")
    }
    private fun inflate(bytes:ByteArray,size:Int):ByteArray = InflaterInputStream(bytes.inputStream()).use { input ->
        val output=ByteArray(size); var at=0
        while(at<size) { val n=input.read(output,at,size-at); require(n>0) { "Truncated ZIP channel" }; at+=n }
        require(input.read()==-1) { "Oversized ZIP channel" }; output
    }
    private class Reader(val data:ByteArray,var at:Int=0) {
        private fun need(n:Int) { require(n>=0 && at>=0 && at<=data.size-n) { "Truncated layer data" } }
        fun u8():Int { need(1); return data[at++].toInt() and 255 }
        fun u16()=(u8() shl 8) or u8()
        fun i16()=u16().toShort().toInt()
        fun i32():Int { need(4); return (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8() }
        fun length():Int=i32().also { require(it>=0) }
        fun sectionEnd():Int { val n=length(); need(n); return at+n }
        fun bytes(n:Int):ByteArray { need(n); return data.copyOfRange(at,at+n).also { at+=n } }
        fun text(n:Int,charset:java.nio.charset.Charset=Charsets.US_ASCII)=bytes(n).toString(charset)
        fun skip(n:Int) { need(n); at+=n }
    }

    fun encodeOra(doc:LayerDocument):ByteArray {
        validate(doc)
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name:String,data:ByteArray,stored:Boolean=false) {
                val e=ZipEntry(name)
                if(stored) { e.method=ZipEntry.STORED; e.size=data.size.toLong(); e.compressedSize=e.size; e.crc=CRC32().apply { update(data) }.value }
                zip.putNextEntry(e); zip.write(data); zip.closeEntry()
            }
            entry("mimetype","image/openraster".toByteArray(),true)
            fun escape(text:String)=text.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;").replace("\n","&#10;").replace("\r","&#13;").replace("\t","&#9;")
            val xml=StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><image version=\"0.0.3\" w=\"${doc.width}\" h=\"${doc.height}\"><stack>")
            doc.layers.forEachIndexed { i,layer ->
                xml.append("<layer name=\"${escape(layer.name)}\" src=\"data/layer$i.png\" x=\"${layer.x}\" y=\"${layer.y}\" opacity=\"${layer.opacity/255.0}\" visibility=\"${if(layer.visible) "visible" else "hidden"}\" composite-op=\"${oraModes.entries.first { it.value==layer.blend }.key}\"/>")
                entry("data/layer$i.png",png(layer.bitmap))
            }
            xml.append("</stack></image>"); entry("stack.xml",xml.toString().toByteArray())
            val merged=composite(doc)
            try { entry("mergedimage.png",png(merged)) } finally { merged.recycle() }
        }; return out.toByteArray()
    }
    private fun png(bitmap:Bitmap)=BitmapFrame(bitmap).use { frame -> ImageEncoding.png(frame).use { it.takeBytes() } }
}
