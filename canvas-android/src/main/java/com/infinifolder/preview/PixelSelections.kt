package com.infinifolder.preview

import android.graphics.*
import kotlin.math.*

/** Coverage in working-image pixels, 0 unselected to 255 fully selected. Caller owns the buffer. */
data class PixelSelection(val width:Int,val height:Int,val coverage:ByteArray) {
    init { PixelGeometry.validate(width,height);require(coverage.size==width*height) }
}

/** Bounded raster selections. No semantic segmentation: background means border-connected color. */
object PixelSelections {
    private fun empty(w:Int,h:Int):PixelSelection {
        PixelGeometry.validate(w,h)
        return PixelSelection(w,h,ByteArray(w*h))
    }
    fun rectangle(w:Int,h:Int,x:Int,y:Int,width:Int,height:Int,ellipse:Boolean=false):PixelSelection {
        val result=empty(w,h)
        require(x in 0 until w && y in 0 until h && width in 1..w-x && height in 1..h-y)
        for(py in y until y+height) for(px in x until x+width) {
            if(!ellipse || ((px+.5-x-width/2.0)/(width/2.0)).pow(2)+((py+.5-y-height/2.0)/(height/2.0)).pow(2)<=1)
                result.coverage[py*w+px]=(-1).toByte()
        };return result
    }
    fun polygon(w:Int,h:Int,points:List<PointF>):PixelSelection {
        require(points.size in 3..4096 && points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..w.toFloat() && it.y in 0f..h.toFloat() })
        val result=empty(w,h)
        val path=Path().apply { moveTo(points[0].x,points[0].y);points.drop(1).forEach { lineTo(it.x,it.y) };close() }
        val region=Region().apply { setPath(path,Region(0,0,w,h)) };val iterator=RegionIterator(region);val r=Rect()
        while(iterator.next(r)) for(y in r.top until r.bottom) result.coverage.fill((-1).toByte(),y*w+r.left,y*w+r.right)
        return result
    }
    private fun near(a:Int,b:Int,tolerance:Int):Boolean {
        if(abs((a ushr 24)-(b ushr 24))>tolerance) return false
        if((a ushr 24)==0 && (b ushr 24)==0) return true
        return abs(((a ushr 16) and 255)-((b ushr 16) and 255))<=tolerance &&
            abs(((a ushr 8) and 255)-((b ushr 8) and 255))<=tolerance && abs((a and 255)-(b and 255))<=tolerance
    }
    private fun pixels(bitmap:Bitmap):IntArray {
        PixelGeometry.validate(bitmap.width,bitmap.height)
        return IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
    }
    fun color(bitmap:Bitmap,x:Int,y:Int,tolerance:Int=24,contiguous:Boolean=true):PixelSelection {
        require(x in 0 until bitmap.width && y in 0 until bitmap.height && tolerance in 0..255)
        val values=pixels(bitmap);val anchor=values[y*bitmap.width+x]
        return matching(bitmap.width,bitmap.height,values,anchor,tolerance,if(contiguous) intArrayOf(y*bitmap.width+x) else null)
    }
    fun background(bitmap:Bitmap,tolerance:Int=24):PixelSelection {
        require(tolerance in 0..255)
        val w=bitmap.width;val h=bitmap.height;val values=pixels(bitmap)
        val histogram=HashMap<Int,Int>();val samples=mutableListOf<Int>()
        fun add(x:Int,y:Int) { val value=values[y*w+x];samples+=value
            val key=if(value ushr 24<16) 0 else (value ushr 28 shl 12) or (((value ushr 20) and 15) shl 8) or (((value ushr 12) and 15) shl 4) or ((value ushr 4) and 15)
            histogram[key]=(histogram[key] ?: 0)+1
        }
        for(x in 0 until w step maxOf(1,w/128)) { add(x,0);add(x,h-1) }
        for(y in 0 until h step maxOf(1,h/128)) { add(0,y);add(w-1,y) }
        fun bucket(value:Int)=if(value ushr 24<16) 0 else (value ushr 28 shl 12) or (((value ushr 20) and 15) shl 8) or (((value ushr 12) and 15) shl 4) or ((value ushr 4) and 15)
        val dominant=histogram.maxByOrNull { it.value }!!.key
        val group=samples.filter { bucket(it)==dominant }
        var anchor=0
        for(shift in listOf(24,16,8,0)) anchor=anchor or (group.map { (it ushr shift) and 255 }.average().roundToInt() shl shift)
        val seeds=IntArray(2*w+2*h);var n=0
        for(x in 0 until w) { seeds[n++]=x;seeds[n++]=(h-1)*w+x }
        for(y in 0 until h) { seeds[n++]=y*w;seeds[n++]=y*w+w-1 }
        return matching(w,h,values,anchor,tolerance,seeds)
    }
    private fun matching(w:Int,h:Int,values:IntArray,anchor:Int,tolerance:Int,seeds:IntArray?):PixelSelection {
        val result=empty(w,h);val mask=result.coverage
        if(seeds==null) { for(i in values.indices) if(near(values[i],anchor,tolerance)) mask[i]=(-1).toByte();return result }
        val queue=IntArray(w*h);var head=0;var tail=0
        fun visit(i:Int) {
            if(mask[i].toInt()!=0) return
            if(near(values[i],anchor,tolerance)) { mask[i]=(-1).toByte();queue[tail++]=i } else mask[i]=1
        }
        seeds.forEach(::visit)
        while(head<tail) {
            if(head and 16383==0 && Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val i=queue[head++];val x=i%w
            if(x>0) visit(i-1);if(x+1<w) visit(i+1);if(i>=w) visit(i-w);if(i<values.size-w) visit(i+w)
        }
        for(i in mask.indices) if(mask[i].toInt()==1) mask[i]=0
        return result
    }
    fun combine(old:PixelSelection?,next:PixelSelection,mode:String):PixelSelection {
        require(mode in setOf("replace","add","subtract","intersect"))
        if(mode=="replace" || old==null) return if(old==null && mode in setOf("subtract","intersect")) empty(next.width,next.height) else next
        require(old.width==next.width && old.height==next.height)
        return PixelSelection(next.width,next.height,ByteArray(next.coverage.size) { i ->
            val a=old.coverage[i].toInt() and 255;val b=next.coverage[i].toInt() and 255
            when(mode) { "add" -> max(a,b);"subtract" -> max(0,a-b);else -> min(a,b) }.toByte()
        })
    }
    fun invert(mask:PixelSelection)=PixelSelection(mask.width,mask.height,ByteArray(mask.coverage.size) { (255-(mask.coverage[it].toInt() and 255)).toByte() })
    fun bounds(mask:PixelSelection):Rect? {
        var l=mask.width;var t=mask.height;var r=0;var b=0
        for(i in mask.coverage.indices) if(mask.coverage[i].toInt()!=0) { val x=i%mask.width;val y=i/mask.width;l=min(l,x);t=min(t,y);r=max(r,x+1);b=max(b,y+1) }
        return if(r>l && b>t) Rect(l,t,r,b) else null
    }
    fun clear(bitmap:Bitmap,mask:PixelSelection):Bitmap {
        require(bitmap.width==mask.width && bitmap.height==mask.height)
        val values=pixels(bitmap)
        for(i in values.indices) { val a=(values[i] ushr 24)*(255-(mask.coverage[i].toInt() and 255))/255;values[i]=(a shl 24) or (values[i] and 0xffffff) }
        return Bitmap.createBitmap(values,mask.width,mask.height,Bitmap.Config.ARGB_8888)
    }
    fun toBitmap(mask:PixelSelection):Bitmap=Bitmap.createBitmap(IntArray(mask.coverage.size) { ((mask.coverage[it].toInt() and 255) shl 24) or 0xffffff },mask.width,mask.height,Bitmap.Config.ARGB_8888)
    fun fromBitmap(bitmap:Bitmap):PixelSelection { val values=pixels(bitmap);return PixelSelection(bitmap.width,bitmap.height,ByteArray(values.size) { (values[it] ushr 24).toByte() }) }
}
