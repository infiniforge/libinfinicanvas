package com.infinifolder.preview

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/** Full composited RGBA frames, ordered in playback order. Hosts own bitmap lifetimes. */
data class AnimationFrame(val bitmap:Bitmap,val delayMillis:Int) {
    init { require(delayMillis in 1..65535000) }
}
data class AnimationDocument(val width:Int,val height:Int,val frames:List<AnimationFrame>,val plays:Int=0)

/** Animation authoring; existing APNG signatures remain compatible. */
object AnimationDocuments {
    fun decode(bytes:ByteArray,targetPixels:Int=2048):AnimationDocument? {
        require(targetPixels>0)
        return PngAnimationDecoder.authoring(bytes,targetPixels) ?: FlicDecoder.authoring(bytes,targetPixels)
    }
    fun validate(doc:AnimationDocument) {
        PixelGeometry.validate(doc.width,doc.height);require(doc.plays>=0)
        require(doc.frames.isNotEmpty()) { "Animation needs at least one frame" }
        require(doc.frames.all { it.bitmap.width==doc.width && it.bitmap.height==doc.height })
    }
    fun player(doc:AnimationDocument):BitmapAnimation {
        validate(doc)
        return object:BitmapAnimation {
            override val width=doc.width
            override val height=doc.height
            override val description="${doc.frames.size} editable frames"
            private val ends=LongArray(doc.frames.size).also { a -> var time=0L; doc.frames.forEachIndexed { i,f -> time=Math.addExact(time,f.delayMillis*1_000_000L); a[i]=time } }
            override val durationNanos=ends.last()
            private var previous=-1
            override fun render(elapsedNanos:Long,output:IntArray,force:Boolean):Boolean {
                val elapsed=elapsedNanos.coerceAtLeast(0)
                val time=if(doc.plays>0 && elapsed/durationNanos>=doc.plays) durationNanos-1 else elapsed%durationNanos
                val index=ends.indexOfFirst { time<it }
                if(previous==index && !force) return false
                previous=index
                doc.frames[index].bitmap.getPixels(output,0,width,0,0,width,height)
                return true
            }
        }
    }
    /** PNG 3: complete frames, SOURCE blend, NONE disposal; preserves alpha and millisecond timing. */
    fun encodeApng(doc:AnimationDocument):ByteArray {
        validate(doc)
        val out=ByteArrayOutputStream()
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun data(action:DataOutputStream.()->Unit)=ByteArrayOutputStream().also { b -> DataOutputStream(b).use { it.action() } }.toByteArray()
        fun chunk(tag:String,bytes:ByteArray) {
            val name=tag.toByteArray(Charsets.US_ASCII)
            DataOutputStream(out).apply { writeInt(bytes.size); write(name); write(bytes); writeInt(CRC32().apply { update(name); update(bytes) }.value.toInt()) }
        }
        // All frames use the same RGBA8 noninterlaced format. Compress through Android's codec.
        chunk("IHDR",data { writeInt(doc.width);writeInt(doc.height);write(byteArrayOf(8,6,0,0,0)) })
        chunk("acTL",data { writeInt(doc.frames.size);writeInt(doc.plays) })
        var sequence=0
        doc.frames.forEachIndexed { index,frame ->
            fun gcd(a:Int,b:Int):Int=if(b==0)a else gcd(b,a%b)
            val common=gcd(frame.delayMillis,1000)
            var numerator=frame.delayMillis/common;var denominator=1000/common
            if(numerator>65535) { denominator=maxOf(1,(65535L*1000/frame.delayMillis).toInt());numerator=kotlin.math.round(frame.delayMillis*denominator/1000.0).toInt() }
            chunk("fcTL",data { writeInt(sequence++);writeInt(doc.width);writeInt(doc.height);writeInt(0);writeInt(0);writeShort(numerator);writeShort(denominator);writeByte(0);writeByte(0) })
            // Force RGBA output even when a source frame was RGB565 or opaque.
            val rgba=Bitmap.createBitmap(doc.width,doc.height,Bitmap.Config.ARGB_8888)
            rgba.setHasAlpha(true)
            android.graphics.Canvas(rgba).drawBitmap(frame.bitmap,0f,0f,null)
            val png=try { ByteArrayOutputStream().also { require(rgba.compress(Bitmap.CompressFormat.PNG,100,it)) }.toByteArray() } finally { rgba.recycle() }
            require(png.size>=33 && png[24].toInt()==8 && png[25].toInt()==6 && png[28].toInt()==0) { "PNG encoder did not produce RGBA8" }
            var at=8
            while(at+12<=png.size) {
                val length=java.nio.ByteBuffer.wrap(png,at,4).int
                require(length>=0 && length<=png.size-at-12)
                val tag=String(png,at+4,4,Charsets.US_ASCII)
                if(tag=="IDAT") {
                    val payload=png.copyOfRange(at+8,at+8+length)
                    if(index==0) chunk("IDAT",payload) else chunk("fdAT",data { writeInt(sequence++);write(payload) })
                }
                at+=length+12
            }
        }
        chunk("IEND",byteArrayOf())
        return out.toByteArray()
    }
}
