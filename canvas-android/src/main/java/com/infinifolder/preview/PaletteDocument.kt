package com.infinifolder.preview

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32

/** An encoded indexed source. Pixel payloads and duplicate palette entries retain their identity. */
class PaletteDocument private constructor(private val original: ByteArray, val format: String,
    val colors: List<Int>, val cycles: List<Cycle>, val editableColors: Int,
    private val chunks: List<Chunk>, private val cycleSlots: Map<Int,Int>) {
    data class Cycle(val first: Int, val last: Int, val millihertz: Int = 6000,
        val reverse: Boolean = false, val enabled: Boolean = true) {
        init { require(first in 0..255 && last in first..255 && millihertz in 1..239000) }
    }
    private data class Chunk(val tag: String, val bytes: ByteArray)

    fun encode(palette: List<Int> = colors, ranges: List<Cycle> = cycles): ByteArray {
        require(palette.size == colors.size && ranges.size <= 256)
        require(ranges.all { it.last < editableColors }) { "Cycle exceeds editable palette" }
        if (format == "png") {
            val out=ByteArrayOutputStream(); out.write(original,0,8)
            chunks.forEach { chunk -> when(chunk.tag) {
                "PLTE" -> {
                    pngChunk(out,"PLTE",ByteArray(palette.size*3) { i -> (palette[i/3] ushr (16-(i%3)*8)).toByte() })
                    if (palette.any { it ushr 24 != 255 }) pngChunk(out,"tRNS",ByteArray(palette.size) { (palette[it] ushr 24).toByte() })
                }
                "tRNS" -> Unit
                // A histogram refers to the unchanged indices, and remains valid.
                else -> pngChunk(out,chunk.tag,chunk.bytes)
            } }
            return out.toByteArray()
        }
        require(palette.indices.all { palette[it] ushr 24 == colors[it] ushr 24 }) { "IFF alpha uses its original mask/transparent index" }
        val changedRanges=ranges!=cycles
        val body=ByteArrayOutputStream(); body.write(original,8,4)
        val written=mutableSetOf<Int>()
        fun writeCycle(index:Int) {
            if(index !in ranges.indices || !written.add(index)) return
            val cycle=ranges[index]
            val rawRate=(cycle.millihertz/1000.0*16384/60).toInt().coerceIn(1,65535)
            // 36 is the historical no-cycling sentinel in otherwise active CRNG records.
            val rate=if(cycle.enabled && rawRate==36) 37 else rawRate
            val data=ByteArrayOutputStream(); DataOutputStream(data).use { d ->
                d.writeShort(0); d.writeShort(rate)
                d.writeShort((if(cycle.enabled) 1 else 0) or (if(cycle.reverse) 2 else 0))
                d.writeByte(cycle.first); d.writeByte(cycle.last)
            }
            iffChunk(body,"CRNG",data.toByteArray())
        }
        chunks.forEachIndexed { chunkIndex,chunk ->
            if(changedRanges && chunk.tag in setOf("CRNG","CCRT")) {
                cycleSlots[chunkIndex]?.let(::writeCycle)
            }
            // Existing ranges retain their order relative to DRNG; new ranges precede BODY.
            if(chunk.tag=="BODY" && changedRanges) ranges.indices.forEach(::writeCycle)
            when {
                chunk.tag=="CMAP" -> iffChunk(body,chunk.tag,ByteArray(palette.size*3) { i -> (palette[i/3] ushr (16-(i%3)*8)).toByte() })
                changedRanges && chunk.tag in setOf("CRNG","CCRT") -> Unit
                else -> iffChunk(body,chunk.tag,chunk.bytes)
            }
        }
        return ByteArrayOutputStream().also { out -> DataOutputStream(out).apply {
            writeBytes("FORM"); writeInt(body.size()); write(body.toByteArray())
        } }.toByteArray()
    }

    companion object {
        fun read(bytes: ByteArray): PaletteDocument? {
            if (bytes.size<12) return null
            fun n(at:Int)=ImageDecodeUtils.u32be(bytes,at)
            val png=bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))
            val iff=String(bytes,0,4,Charsets.US_ASCII)=="FORM" && String(bytes,8,4,Charsets.US_ASCII) in setOf("ILBM","PBM ")
            if (!png && !iff) return null
            val chunks=mutableListOf<Chunk>()
            var at=if(png) 8 else 12
            val end=if(png) bytes.size else (n(4)+8).also { require(it<=bytes.size && it>=12) }.toInt()
            while(at+8<=end) {
                val size=n(at+(if(png) 0 else 4))
                val tag=String(bytes,at+(if(png) 4 else 0),4,Charsets.US_ASCII)
                val extra=if(png) 4 else (size and 1).toInt()
                require(size<=end-at-8-extra) { "Truncated $tag chunk" }
                val data=bytes.copyOfRange(at+8,at+8+size.toInt())
                if(png) {
                    val crc=CRC32().apply { update(tag.toByteArray(Charsets.US_ASCII)); update(data) }.value
                    require(crc==n(at+8+size.toInt())) { "PNG checksum mismatch in $tag" }
                }
                chunks+=Chunk(tag,data); at+=8+size.toInt()+extra
                if(png && tag=="IEND") break
            }
            if(png) {
                val header=chunks.singleOrNull { it.tag=="IHDR" }?.bytes ?: return null
                require(header.size==13)
                if(header[9].toInt()!=3) return null
                require(chunks.any { it.tag=="IDAT" } && chunks.any { it.tag=="IEND" })
            } else {
                val header=chunks.singleOrNull { it.tag=="BMHD" }?.bytes ?: return null
                require(header.size>=20 && chunks.any { it.tag=="BODY" })
                if((header[8].toInt() and 255)>8) return null
                require(chunks.none { it.tag in setOf("PCHG","SHAM","CTBL","DYCP") }) { "Scanline palettes are not editable" }
            }
            val table=chunks.singleOrNull { it.tag==if(png) "PLTE" else "CMAP" }?.bytes ?: return null
            require(table.isNotEmpty() && table.size%3==0 && table.size<=768)
            val alpha=chunks.firstOrNull { it.tag=="tRNS" }?.bytes
            val colors=List(table.size/3) { i ->
                ((if(png && alpha!=null && i<alpha.size) alpha[i].toInt() and 255 else 255) shl 24) or
                    ((table[i*3].toInt() and 255) shl 16) or ((table[i*3+1].toInt() and 255) shl 8) or (table[i*3+2].toInt() and 255)
            }
            var editable=colors.size
            if(iff) {
                val planes=chunks.first { it.tag=="BMHD" }.bytes[8].toInt() and 255
                val camg=chunks.firstOrNull { it.tag=="CAMG" }?.bytes?.let { if(it.size>=4) ImageDecodeUtils.u32be(it,0) else 0 } ?: 0
                editable=minOf(editable,if(camg and 0x800L!=0L && planes in setOf(6,8)) 1 shl (planes-2) else if(camg and 0x80L!=0L && planes==6) 32 else 1 shl planes)
            }
            data class Record(val chunk:Int,val kind:String,val cycle:Cycle)
            val records=mutableListOf<Record>()
            for((chunkIndex,chunk) in chunks.withIndex()) {
                val d=chunk.bytes
                val c=when {
                    chunk.tag=="CRNG" && d.size>=8 && (d[6].toInt() and 255)<=(d[7].toInt() and 255) -> {
                        val rate=ImageDecodeUtils.u16be(d,2); val flags=ImageDecodeUtils.u16be(d,4)
                        Cycle(d[6].toInt() and 255,d[7].toInt() and 255,
                            (rate*60000.0/16384).toInt().coerceIn(1,239000),flags and 2!=0,flags and 1!=0 && rate!=0 && rate!=36)
                    }
                    chunk.tag=="CCRT" && d.size>=14 && (d[2].toInt() and 255)<=(d[3].toInt() and 255) -> {
                        val direction=ImageDecodeUtils.u16be(d,0).toShort().toInt()
                        val period=ImageDecodeUtils.u32be(d,4)*1_000_000+ImageDecodeUtils.u32be(d,8)
                        Cycle(d[2].toInt() and 255,d[3].toInt() and 255,
                            (if(period>0) 1_000_000_000.0/period else 1.0).toInt().coerceIn(1,239000),direction<0,direction!=0)
                    }
                    else -> null
                }
                if(c!=null && c.last<editable) records+=Record(chunkIndex,chunk.tag,c)
            }
            fun same(a:Cycle,b:Cycle)=a.first==b.first && a.last==b.last
            val retained=records.filter { record -> record.kind!="CCRT" || records.none {
                it.kind=="CRNG" && it.cycle.enabled && same(it.cycle,record.cycle)
            } }
            require(retained.size<=256) { "Too many cycling ranges" }
            val slots=records.associate { record -> record.chunk to retained.indexOfFirst {
                it.chunk==record.chunk || (record.kind=="CCRT" && it.kind=="CRNG" && it.cycle.enabled && same(it.cycle,record.cycle))
            } }
            return PaletteDocument(bytes.copyOf(),if(png) "png" else "iff",colors,retained.map { it.cycle },editable,chunks,slots)
        }
        private fun pngChunk(out:ByteArrayOutputStream,tag:String,data:ByteArray) {
            val type=tag.toByteArray(Charsets.US_ASCII)
            val crc=CRC32().apply { update(type); update(data) }
            DataOutputStream(out).apply { writeInt(data.size); write(type); write(data); writeInt(crc.value.toInt()) }
        }
        private fun iffChunk(out:ByteArrayOutputStream,tag:String,data:ByteArray) {
            DataOutputStream(out).apply { writeBytes(tag); writeInt(data.size); write(data); if(data.size%2==1) writeByte(0) }
        }
    }
}
