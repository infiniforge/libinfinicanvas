package com.infinifolder.preview

import java.io.*

/** GIF89a full frames, fixed RGB332 palette, binary alpha, centisecond delays.
 * Frequent LZW clear codes keep streaming memory constant at the cost of compression ratio. */
internal object GifImageWriter {
    fun write(doc:AnimationDocument,output:OutputStream) {
        AnimationDocuments.validate(doc)
        require(doc.width<=65535 && doc.height<=65535) { "GIF's 16-bit dimensions are exceeded" }
        require(doc.plays<=65536) { "GIF loop count exceeds its 16-bit repeat extension" }
        val out=DataOutputStream(output)
        fun le16(n:Int) { out.writeByte(n);out.writeByte(n ushr 8) }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII));le16(doc.width);le16(doc.height);out.writeByte(0xf7);out.writeByte(0);out.writeByte(0)
        for(i in 0..255) { val q=if(i==0)0 else if(i==255)255 else i-1;out.writeByte(((q ushr 5) and 7)*255/7);out.writeByte(((q ushr 2) and 7)*255/7);out.writeByte((q and 3)*85) }
        if(doc.frames.size>1 && doc.plays!=1) { out.write(byteArrayOf(0x21,0xff.toByte(),11));out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII));out.write(byteArrayOf(3,1));le16(if(doc.plays==0)0 else doc.plays-1);out.writeByte(0) }
        val row=IntArray(doc.width)
        for(frame in doc.frames) {
            require(frame.delayMillis<=655350) { "GIF frame duration exceeds its 16-bit centisecond field" }
            out.write(byteArrayOf(0x21,0xf9.toByte(),4,9));le16(maxOf(1,(frame.delayMillis+5)/10));out.writeByte(0);out.writeByte(0)
            out.writeByte(0x2c);le16(0);le16(0);le16(doc.width);le16(doc.height);out.writeByte(0);out.writeByte(8)
            val block=ByteArray(255);var count=0;var bits=0;var available=0
            fun byte(value:Int) { block[count++]=value.toByte();if(count==255) { out.writeByte(count);out.write(block);count=0 } }
            fun code(value:Int) { bits=bits or (value shl available);available+=9;while(available>=8) { byte(bits and 255);bits=bits ushr 8;available-=8 } }
            for(y in 0 until doc.height) {
                if(Thread.currentThread().isInterrupted) throw InterruptedIOException()
                frame.bitmap.getPixels(row,0,doc.width,0,y,doc.width,1)
                for(c in row) { val q=(((c ushr 21) and 7) shl 5) or (((c ushr 13) and 7) shl 2) or ((c ushr 6) and 3);code(256);code(if(c ushr 24<128)0 else minOf(255,q+1)) }
            }
            code(257);if(available>0) byte(bits and 255)
            if(count>0) { out.writeByte(count);out.write(block,0,count) };out.writeByte(0)
        }
        out.writeByte(0x3b)
    }
}
