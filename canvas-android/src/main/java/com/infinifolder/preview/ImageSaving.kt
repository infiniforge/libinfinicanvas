package com.infinifolder.preview

import android.graphics.*
import android.os.Build
import io.github.gretchycat.infinistream.*
import java.io.*

/** A real writer target, separate from formats that only have decoders. */
data class ImageSaveFormat(val id:String,val mimeType:String,val extensions:Set<String>,val alpha:Boolean=true,
    val layers:Boolean=false,val animation:Boolean=false,val indexed:Boolean=false,val paletteCycling:Boolean=false,val limitations:String="")

/** Extension point for linked encoder modules. Streams and pixels are borrowed; implementations must not close them. */
interface ImageWriter : CapabilityHandler {
    val saveFormats:List<ImageSaveFormat>
    fun write(bitmap:Bitmap,format:String,output:OutputStream,quality:Int=95,background:Int=Color.WHITE)
    fun writeDocument(document:LayerDocument,format:String,output:OutputStream) {
        val bitmap=LayerDocuments.composite(document)
        try { write(bitmap,format,output) } finally { bitmap.recycle() }
    }
    fun writePalette(document:PaletteDocument,colors:List<Int>,cycles:List<PaletteDocument.Cycle>,output:OutputStream) {
        throw IOException("No indexed palette encoder is installed for ${document.format}")
    }
    fun writeAnimation(document:AnimationDocument,format:String,output:OutputStream) {
        throw IOException("No animation encoder is installed for $format")
    }
}

/** Format-aware saving. No file publication or source overwrite: hosts supply their destination. */
object ImageSaving {
    private fun writers()=CanvasCapabilities.registry.registeredHandlers(Operations.ENCODE).mapNotNull { it.service(ImageWriter::class.java) }
    val formats:List<ImageSaveFormat> get()=writers().flatMap { it.saveFormats }.distinctBy { it.id }
    fun format(extension:String):ImageSaveFormat? {
        val key=extension.lowercase(java.util.Locale.ROOT).removePrefix(".")
        return formats.firstOrNull { key in it.extensions || it.id==key }
    }
    private fun selected(format:String):Pair<ImageWriter,ImageSaveFormat> {
        val selected=this.format(format) ?: throw IOException("No encoder is installed for $format")
        return (writers().firstOrNull { it.saveFormats.any { f->f.id==selected.id } } ?: throw IOException("Encoder unavailable: $format")) to selected
    }
    @JvmStatic @JvmOverloads fun write(bitmap:Bitmap,format:String,output:OutputStream,quality:Int=95,background:Int=Color.WHITE) {
        val (writer,target)=selected(format);val buffered=BufferedOutputStream(output,65536)
        writer.write(bitmap,target.id,buffered,quality,background);buffered.flush()
    }
    @JvmStatic fun writeAnimationProject(document:AnimationProject,output:OutputStream) {
        val buffered=BufferedOutputStream(output,65536);AnimationProjects.write(document,buffered);buffered.flush()
    }
    /** Extended ORA: original media bytes, layers, groups, unknown entries and extension XML. */
    @JvmStatic fun writeOpenRaster(document:OpenRasterDocument,output:OutputStream) {
        val buffered=BufferedOutputStream(output,65536);OpenRasterDocuments.write(document,buffered);buffered.flush()
    }
    @JvmStatic fun writeDocument(document:LayerDocument,format:String,output:OutputStream) {
        val (writer,target)=selected(format);val buffered=BufferedOutputStream(output,65536)
        writer.writeDocument(document,target.id,buffered);buffered.flush()
    }
    /** Preserve original indexed pixels and IFF cycle chunks/ranges; no RGB-to-index reconstruction. */
    @JvmStatic @JvmOverloads fun writePalette(document:PaletteDocument,output:OutputStream,
        colors:List<Int> = document.colors,cycles:List<PaletteDocument.Cycle> = document.cycles) {
        val (writer,target)=selected(document.format)
        if(!target.indexed) throw IOException("No indexed palette encoder is installed for ${document.format}")
        val buffered=BufferedOutputStream(output,65536)
        writer.writePalette(document,colors,cycles,buffered);buffered.flush()
    }
    @JvmStatic fun writeAnimation(document:AnimationDocument,format:String,output:OutputStream) {
        val (writer,target)=selected(format);val buffered=BufferedOutputStream(output,65536)
        writer.writeAnimation(document,target.id,buffered);buffered.flush()
    }
}

/** Standard raster writers with row-wise output; encoded containers may buffer their metadata/data. */
class BuiltinImageWriter : ImageWriter {
    override val saveFormats=listOf(
        ImageSaveFormat("icanim",AnimationProjects.MIME_TYPE,setOf("icanim"),layers=true,animation=true,limitations="ZIP/XML timeline with independent ORA frame documents."),
        ImageSaveFormat("png","image/png",setOf("png"),indexed=true),
        ImageSaveFormat("jpeg","image/jpeg",setOf("jpg","jpeg","jpe","jfif"),alpha=false,limitations="Lossy RGB; transparency is composited over the chosen background."),
        ImageSaveFormat("webp","image/webp",setOf("webp"),limitations="Still lossless WebP on Android 11+; older Android uses lossy WebP."),
        ImageSaveFormat("bmp","image/bmp",setOf("bmp"),limitations="32-bit BGRA BITMAPV4 with explicit alpha masks."),
        ImageSaveFormat("tga","image/x-tga",setOf("tga","icb","vda","vst"),limitations="Uncompressed top-origin RGBA; TGA dimensions use unsigned 16-bit fields."),
        ImageSaveFormat("qoi","image/x-qoi",setOf("qoi")),ImageSaveFormat("ff","image/x-farbfeld",setOf("ff")),
        ImageSaveFormat("ppm","image/x-portable-pixmap",setOf("ppm","pnm"),alpha=false,limitations="RGB pixels; alpha is composited over the chosen background."),
        ImageSaveFormat("pgm","image/x-portable-graymap",setOf("pgm"),alpha=false,limitations="8-bit grayscale; alpha is composited over the chosen background."),
        ImageSaveFormat("pbm","image/x-portable-bitmap",setOf("pbm"),alpha=false,limitations="Thresholded 1-bit monochrome; alpha is composited over the chosen background."),
        ImageSaveFormat("pam","image/x-portable-arbitrarymap",setOf("pam")),
        ImageSaveFormat("tiff","image/tiff",setOf("tif","tiff"),limitations="Single-page uncompressed RGBA8 TIFF."),
        ImageSaveFormat("iff","image/x-ilbm",setOf("iff","ilbm","lbm"),indexed=true,paletteCycling=true,limitations="24-bit ILBM with a binary mask; partial alpha is thresholded. Original indices/cycles require PaletteDocument.encode."),
        ImageSaveFormat("ora","image/openraster",setOf("ora"),layers=true,limitations="Supported raster stack only; source metadata/groups/effects are omitted."),
        ImageSaveFormat("psd","image/vnd.adobe.photoshop",setOf("psd"),layers=true,limitations="PSD v1 RGB8 raster layers and merged RGBA; source groups, masks, effects and metadata are omitted."),
        ImageSaveFormat("xcf","image/x-xcf",setOf("xcf"),layers=true,limitations="XCF v0 RGBA8 normal raster layers; other blends are rejected. Source groups/masks/effects/metadata are omitted."),
        ImageSaveFormat("gif","image/gif",setOf("gif"),animation=true,limitations="RGB332 palette quantization, binary alpha and centisecond timing; compression favors constant working memory."),
        ImageSaveFormat("svgz","image/svg+xml",setOf("svgz"),limitations="Gzip SVG containing PNG pixels; original vector objects and metadata are omitted."),
        ImageSaveFormat("svg","image/svg+xml",setOf("svg"),limitations="PNG pixels embedded in an SVG document; vector objects and source metadata are not retained."),
        ImageSaveFormat("apng","image/apng",setOf("apng"),animation=true,limitations="Complete RGBA8 frames with timing; source patches and metadata are omitted."))
    override val descriptor=HandlerDescriptor("canvas.save.raster","canvas","Image format writers",
        saveFormats.map { FormatSupport(setOf(it.mimeType),it.extensions) },setOf(Operations.ENCODE),
        limitations=listOf("Actual writer targets only; decoded raster pixels, not original document fidelity", "Caller owns destination stream and publication"))
    override fun availability()=HandlerAvailability(AvailabilityState.CONDITIONAL,"Requires Android bitmap backend and destination access")
    override fun writeDocument(document:LayerDocument,format:String,output:OutputStream) {
        when(format) {
            "ora" -> output.write(LayerDocuments.encodeOra(document))
            "icanim" -> AnimationProjects.write(AnimationProject(document.width,document.height,listOf(AnimationProjectFrame("frame-0","Frame 1",OpenRasterDocuments.fromDocument(document),100))),output)
            "psd" -> LayerImageWriters.psd(document,output)
            "xcf" -> LayerImageWriters.xcf(document,output)
            else -> super<ImageWriter>.writeDocument(document,format,output)
        }
    }
    override fun writePalette(document:PaletteDocument,colors:List<Int>,cycles:List<PaletteDocument.Cycle>,output:OutputStream) {
        if(document.format !in setOf("png","iff")) throw IOException("Unsupported indexed format: ${document.format}")
        output.write(document.encode(colors,cycles))
    }
    override fun writeAnimation(document:AnimationDocument,format:String,output:OutputStream) {
        when(format) { "icanim" -> AnimationProjects.write(AnimationProjects.fromAnimation(document),output);"apng" -> output.write(AnimationDocuments.encodeApng(document));"gif" -> GifImageWriter.write(document,output);else -> super<ImageWriter>.writeAnimation(document,format,output) }
    }
    override fun write(bitmap:Bitmap,format:String,output:OutputStream,quality:Int,background:Int) {
        require(quality in 0..100);PixelGeometry.validate(bitmap.width,bitmap.height)
        val f=saveFormats.firstOrNull { it.id==format } ?: throw IOException("Unsupported encoder: $format")
        if(f.layers) { writeDocument(LayerDocument(bitmap.width,bitmap.height,listOf(ImageLayer("layer-0","Background",bitmap))),format,output);return }
        if(format in setOf("apng","gif")) { writeAnimation(AnimationDocument(bitmap.width,bitmap.height,listOf(AnimationFrame(bitmap,100))),format,output);return }
        val out=DataOutputStream(output);val w=bitmap.width;val h=bitmap.height
        fun le16(n:Int) { out.writeByte(n);out.writeByte(n ushr 8) }
        fun le32(n:Int) { le16(n);le16(n ushr 16) }
        fun text(s:String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
        fun rgb(c:Int):Int { val a=c ushr 24;return (0xff shl 24) or ((((c ushr 16 and 255)*a+(background ushr 16 and 255)*(255-a)+127)/255) shl 16) or ((((c ushr 8 and 255)*a+(background ushr 8 and 255)*(255-a)+127)/255) shl 8) or (((c and 255)*a+(background and 255)*(255-a)+127)/255) }
        when(format) {
            "png","jpeg","webp" -> {
                val source=if(format=="jpeg" && bitmap.hasAlpha()) Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also { val canvas=Canvas(it);canvas.drawColor(background or (0xff shl 24));canvas.drawBitmap(bitmap,0f,0f,null) } else bitmap
                val codec=when(format) { "png"->Bitmap.CompressFormat.PNG;"jpeg"->Bitmap.CompressFormat.JPEG;else->if(Build.VERSION.SDK_INT>=30) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP }
                var failure:IOException?=null
                val checked=object:OutputStream() {
                    override fun write(v:Int) { try { interrupted();out.write(v) } catch(e:IOException) { failure=e;throw e } }
                    override fun write(b:ByteArray,o:Int,n:Int) { try { interrupted();out.write(b,o,n) } catch(e:IOException) { failure=e;throw e } }
                }
                try { val success=source.compress(codec,quality,checked);failure?.let { throw it };if(!success) throw IOException("$format encoder failed") }
                finally { if(source!==bitmap) source.recycle() };return
            }
            "svgz" -> { java.util.zip.GZIPOutputStream(object:FilterOutputStream(output) { override fun close() { flush() } }).use { gzip -> write(bitmap,"svg",gzip,quality,background) };return }
            "svg" -> {
                val png=ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,png)) throw IOException("PNG encoder failed")
                text("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\"><image width=\"$w\" height=\"$h\" xlink:href=\"data:image/png;base64,")
                val encoder=android.util.Base64OutputStream(object:FilterOutputStream(out) { override fun close() { flush() } },android.util.Base64.NO_WRAP)
                png.writeTo(encoder);encoder.close();text("\"/></svg>");return
            }
            "bmp" -> { val bytes=w.toLong()*h*4;require(bytes+122<=0xffffffffL) { "BMP's 32-bit length field is exceeded" };text("BM");le32((bytes+122).toInt());le32(0);le32(122);le32(108);le32(w);le32(-h);le16(1);le16(32);le32(3);le32(bytes.toInt());le32(2835);le32(2835);le32(0);le32(0);le32(0xff0000);le32(0xff00);le32(255);le32(0xff000000.toInt());le32(0x73524742);repeat(12) { le32(0) } }
            "tga" -> { require(w<=65535 && h<=65535) { "TGA's unsigned 16-bit dimensions are exceeded" };out.write(ByteArray(12).apply { this[2]=2 });le16(w);le16(h);out.writeByte(32);out.writeByte(0x28) }
            "qoi" -> { text("qoif");out.writeInt(w);out.writeInt(h);out.writeByte(4);out.writeByte(0) }
            "ff" -> { text("farbfeld");out.writeInt(w);out.writeInt(h) }
            "ppm" -> text("P6\n$w $h\n255\n")
            "pgm" -> text("P5\n$w $h\n255\n")
            "pbm" -> text("P4\n$w $h\n")
            "pam" -> text("P7\nWIDTH $w\nHEIGHT $h\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n")
            "tiff" -> {
                val offset=8+2+11*12+4;val pixels=offset+8
                text("II");le16(42);le32(8);le16(11)
                fun tag(id:Int,type:Int,count:Int,value:Int) { le16(id);le16(type);le32(count);le32(value) }
                tag(256,4,1,w);tag(257,4,1,h);tag(258,3,4,offset);tag(259,3,1,1);tag(262,3,1,2);tag(273,4,1,pixels);tag(277,3,1,4);tag(278,4,1,h);tag(279,4,1,w*h*4);tag(284,3,1,1);tag(338,3,1,2);le32(0);repeat(4) { le16(8) }
            }
            "iff" -> {
                require(w<=65535 && h<=65535) { "ILBM's unsigned 16-bit dimensions are exceeded" }
                val stride=((w+15)/16)*2;val length=stride.toLong()*25*h;require(length+40<=0xffffffffL) { "IFF's 32-bit FORM length is exceeded" }
                text("FORM");out.writeInt((length+40).toInt());text("ILBM");text("BMHD");out.writeInt(20);out.writeShort(w);out.writeShort(h);out.writeInt(0);out.writeByte(24);out.writeByte(1);out.writeByte(0);out.writeByte(0);out.writeShort(0);out.writeByte(1);out.writeByte(1);out.writeShort(w);out.writeShort(h);text("BODY");out.writeInt(length.toInt())
            }
            else -> throw IOException("Unsupported encoder: $format")
        }
        val row=IntArray(w);val packed=if(format=="iff") ByteArray(((w+15)/16)*2*25) else null
        for(y in 0 until h) {
            interrupted();bitmap.getPixels(row,0,w,0,y,w,1)
            if(format=="iff") {
                val data=packed!!;data.fill(0);val stride=data.size/25
                for(x in row.indices) { val c=row[x];for(p in 0..24) { val bit=if(p==24) if(c ushr 24>=128)1 else 0 else (c ushr (if(p<8)p+16 else if(p<16)p else p-16)) and 1
                    if(bit!=0) { val i=p*stride+x/8;data[i]=(data[i].toInt() or (128 ushr (x and 7))).toByte() } } };out.write(data);continue
            }
            if(format=="pbm") { var bits=0;var n=0;for(c0 in row) { val c=rgb(c0);val gray=((c ushr 16 and 255)*77+(c ushr 8 and 255)*150+(c and 255)*29) ushr 8;bits=(bits shl 1) or if(gray<128)1 else 0;n++;if(n==8) { out.writeByte(bits);bits=0;n=0 } };if(n>0) out.writeByte(bits shl (8-n));continue }
            for(c0 in row) {
                val c=if(f.alpha)c0 else rgb(c0);val r=c ushr 16 and 255;val g=c ushr 8 and 255;val b=c and 255;val a=c ushr 24
                when(format) {
                    "bmp","tga" -> { out.writeByte(b);out.writeByte(g);out.writeByte(r);out.writeByte(a) }
                    "qoi" -> { out.writeByte(255);out.writeByte(r);out.writeByte(g);out.writeByte(b);out.writeByte(a) }
                    "ff" -> { out.writeShort(r*257);out.writeShort(g*257);out.writeShort(b*257);out.writeShort(a*257) }
                    "pgm" -> out.writeByte((r*77+g*150+b*29) ushr 8)
                    else -> { out.writeByte(r);out.writeByte(g);out.writeByte(b);if(f.alpha) out.writeByte(a) }
                }
            }
        }
        if(format=="qoi") out.write(byteArrayOf(0,0,0,0,0,0,0,1))
    }
    private fun interrupted() { if(Thread.currentThread().isInterrupted) throw InterruptedIOException() }
}
