package com.infinifolder.preview

import android.graphics.Bitmap
import java.io.*

/** RGB8 raster subsets of the published Adobe PSD and GIMP XCF specifications. */
internal object LayerImageWriters {
    fun psd(doc:LayerDocument,output:OutputStream) {
        LayerDocuments.validate(doc)
        require(doc.width<=30000 && doc.height<=30000 && doc.layers.size<=32767) { "PSD v1 dimension/layer fields are exceeded; use OpenRaster" }
        val blends=mapOf("normal" to "norm","multiply" to "mul ","screen" to "scrn","darken" to "dark","lighten" to "lite")
        val records=ByteArrayOutputStream();val r=DataOutputStream(records)
        fun text(out:DataOutputStream,s:String)=out.write(s.toByteArray(Charsets.US_ASCII))
        var pixels=0L
        for(layer in doc.layers) {
            val w=layer.bitmap.width;val h=layer.bitmap.height;require(w<=30000 && h<=30000)
            r.writeInt(layer.y);r.writeInt(layer.x);r.writeInt(Math.addExact(layer.y,h));r.writeInt(Math.addExact(layer.x,w));r.writeShort(4)
            for(id in intArrayOf(0,1,2,-1)) { r.writeShort(id);r.writeInt(w*h+2) };pixels+=4L*(w.toLong()*h+2)
            text(r,"8BIM");text(r,blends[layer.blend] ?: error("Unsupported PSD blend"));r.writeByte(layer.opacity);r.writeByte(0);r.writeByte(if(layer.visible)0 else 2);r.writeByte(0)
            val extra=ByteArrayOutputStream();val e=DataOutputStream(extra);e.writeInt(0);e.writeInt(0)
            val name=layer.name.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray();e.writeByte(name.size);e.write(name);repeat((4-(name.size+1)%4)%4) { e.writeByte(0) }
            text(e,"8BIM");text(e,"luni");e.writeInt(4+layer.name.length*2);e.writeInt(layer.name.length);e.write(layer.name.toByteArray(Charsets.UTF_16BE))
            r.writeInt(extra.size());extra.writeTo(r)
        }
        val info=2L+records.size()+pixels;val padded=(info+1)/2*2;require(padded+8<=Int.MAX_VALUE) { "PSD layer section exceeds this writer's addressable buffer" }
        val out=DataOutputStream(output);text(out,"8BPS");out.writeShort(1);out.write(ByteArray(6));out.writeShort(4);out.writeInt(doc.height);out.writeInt(doc.width);out.writeShort(8);out.writeShort(3)
        out.writeInt(0);out.writeInt(0);out.writeInt((padded+8).toInt());out.writeInt(padded.toInt());out.writeShort(-doc.layers.size);records.writeTo(out)
        fun channel(bitmap:Bitmap,shift:Int) { val row=IntArray(bitmap.width);val bytes=ByteArray(row.size);for(y in 0 until bitmap.height) { interrupted();bitmap.getPixels(row,0,row.size,0,y,row.size,1);for(x in row.indices) bytes[x]=(row[x] ushr shift).toByte();out.write(bytes) } }
        for(layer in doc.layers) for(shift in intArrayOf(16,8,0,24)) { out.writeShort(0);channel(layer.bitmap,shift) }
        if(info!=padded) out.writeByte(0);out.writeInt(0)
        val merged=LayerDocuments.composite(doc)
        try { out.writeShort(0);for(shift in intArrayOf(16,8,0,24)) channel(merged,shift) } finally { merged.recycle() }
    }
    private class PatchBuffer:ByteArrayOutputStream() {
        fun patch(at:Int,value:Int) { for(i in 0..3) buf[at+i]=(value ushr (24-i*8)).toByte() }
    }
    fun xcf(doc:LayerDocument,output:OutputStream) {
        LayerDocuments.validate(doc);require(doc.layers.all { it.blend=="normal" }) { "XCF writer currently supports normal raster layers; use PSD or OpenRaster for other blends" }
        val buffer=PatchBuffer();val out=DataOutputStream(buffer)
        fun string(s:String) { val bytes=s.toByteArray(Charsets.UTF_8);out.writeInt(bytes.size+1);out.write(bytes);out.writeByte(0) }
        fun prop(id:Int,vararg values:Int) { out.writeInt(id);out.writeInt(values.size*4);values.forEach(out::writeInt) }
        fun end() { out.writeInt(0);out.writeInt(0) }
        out.write("gimp xcf file\u0000".toByteArray(Charsets.US_ASCII));out.writeInt(doc.width);out.writeInt(doc.height);out.writeInt(0)
        out.writeInt(17);out.writeInt(1);out.writeByte(0);end()
        val layerTable=buffer.size();doc.layers.forEach { out.writeInt(0) };out.writeInt(0);out.writeInt(0)
        doc.layers.forEachIndexed { index,layer ->
            interrupted();buffer.patch(layerTable+index*4,buffer.size())
            val w=layer.bitmap.width;val h=layer.bitmap.height
            out.writeInt(w);out.writeInt(h);out.writeInt(1);string(layer.name)
            prop(6,layer.opacity);prop(7,0);prop(8,if(layer.visible)1 else 0);prop(15,layer.x,layer.y);end()
            val hierarchy=buffer.size();out.writeInt(0);out.writeInt(0);buffer.patch(hierarchy,buffer.size())
            out.writeInt(w);out.writeInt(h);out.writeInt(4);val level=buffer.size();out.writeInt(0);out.writeInt(0);buffer.patch(level,buffer.size())
            out.writeInt(w);out.writeInt(h);val tiles=buffer.size();val cols=(w+63)/64;val rows=(h+63)/64
            repeat(Math.multiplyExact(cols,rows)) { out.writeInt(0) };out.writeInt(0)
            val row=IntArray(minOf(64,w))
            for(ty in 0 until rows) for(tx in 0 until cols) {
                interrupted();buffer.patch(tiles+(ty*cols+tx)*4,buffer.size());val tw=minOf(64,w-tx*64);val th=minOf(64,h-ty*64)
                for(py in 0 until th) { layer.bitmap.getPixels(row,0,tw,tx*64,ty*64+py,tw,1);for(px in 0 until tw) { val c=row[px];out.writeByte(c ushr 16);out.writeByte(c ushr 8);out.writeByte(c);out.writeByte(c ushr 24) } }
            }
        }
        buffer.writeTo(output)
    }
    private fun interrupted() { if(Thread.currentThread().isInterrupted) throw InterruptedIOException() }
}
