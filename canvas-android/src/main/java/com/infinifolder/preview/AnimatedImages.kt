package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import io.github.gretchycat.infinistream.Provenance
import java.nio.ByteBuffer

/** Shared full-viewer animation entry point. Icons continue to request a still frame. */
internal object AnimatedImages {
    fun decode(bytes:ByteArray,targetPixels:Int,source:String):BitmapFrame? {
        val provenance=Provenance(source=source,transformations=listOf("image.decode.animation"))
        FlicDecoder.decode(bytes,targetPixels)?.let {image->
            return BitmapFrame(image.bitmap,provenance=provenance,warnings=image.warnings).also{it.animation=image.animation}
        }
        PngAnimationDecoder.decode(bytes,targetPixels)?.let{image->
            return BitmapFrame(image.bitmap,provenance=provenance,warnings=image.warnings).also{it.animation=image.animation}
        }
        val ext=ImageSignatures.extension(bytes)?:return null
        if(ext !in setOf("gif","webp","avif","heif"))return null
        if(Build.VERSION.SDK_INT>=28){
            val drawable=ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))){decoder,info,_->
                require(info.size.width.toLong()*info.size.height<=100_000_000){"Animated image exceeds source pixel limit"}
                val scale=minOf(1.0,targetPixels.toDouble()/maxOf(info.size.width,info.size.height),
                    kotlin.math.sqrt(4.0*1024*1024/(info.size.width.toDouble()*info.size.height)))
                decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
            if(drawable is AnimatedImageDrawable){
                drawable.repeatCount=AnimatedImageDrawable.REPEAT_INFINITE
                // Decode a still poster separately for editing and initial layout.
                val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))){decoder,_,_->
                    decoder.setTargetSize(drawable.intrinsicWidth,drawable.intrinsicHeight)
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.isMutableRequired=true
                }
                val displayedDrawable = if (ext == "gif" || ext == "webp")
                    BitmapBackedAnimationDrawable(drawable, bitmap.width, bitmap.height)
                else drawable
                return BitmapFrame(bitmap,provenance=provenance).also{
                    it.animationDrawable=displayedDrawable
                    it.animationDurationNanos=NativeAnimationTiming.durationNanos(bytes,ext)
                }
            }
            if(drawable is android.graphics.drawable.BitmapDrawable)return BitmapFrame(drawable.bitmap,provenance=provenance)
        }else if(ext=="gif"){
            @Suppress("DEPRECATION")
            val movie=Movie.decodeByteArray(bytes,0,bytes.size)?:return null
            require(movie.width()>0 && movie.height()>0 && movie.width().toLong()*movie.height()<=4*1024*1024){"GIF exceeds pixel limit"}
            val scale=minOf(1.0,targetPixels.toDouble()/maxOf(movie.width(),movie.height()))
            val bitmap=Bitmap.createBitmap(maxOf(1,(movie.width()*scale).toInt()),maxOf(1,(movie.height()*scale).toInt()),Bitmap.Config.ARGB_8888)
            val renderer=LegacyGif(movie,bitmap.width,bitmap.height)
            val pixels=IntArray(bitmap.width*bitmap.height)
            renderer.render(0,pixels,true);bitmap.setPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            return BitmapFrame(bitmap,provenance=provenance,warnings=listOf("Legacy Android GIF playback loops continuously")).also{it.animation=renderer}
        }
        return null
    }

    @Suppress("DEPRECATION")
    private class LegacyGif(val movie:Movie,override val width:Int,override val height:Int):BitmapAnimation {
        override val description="Animated GIF"
        override val durationNanos get()=movie.duration().coerceAtLeast(1)*1_000_000L
        private val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        private val canvas=Canvas(bitmap)
        private var previous=-1
        override fun render(elapsedNanos:Long,output:IntArray,force:Boolean):Boolean {
            val time=(elapsedNanos.coerceAtLeast(0)/1_000_000%movie.duration().coerceAtLeast(1)).toInt()
            if(time==previous && !force)return false
            previous=time;movie.setTime(time);bitmap.eraseColor(0)
            canvas.save();canvas.scale(width.toFloat()/movie.width(),height.toFloat()/movie.height())
            movie.draw(canvas,0f,0f);canvas.restore()
            bitmap.getPixels(output,0,width,0,0,width,height)
            return true
        }
    }
}


/** Render native GIF/WebP frames to a software bitmap before the viewer's hardware canvas displays them. */
private class BitmapBackedAnimationDrawable(
    private val source: Drawable,
    private val width: Int,
    private val height: Int
) : Drawable(), Animatable, Drawable.Callback {
    private val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val frameCanvas = Canvas(frameBitmap)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var alpha = 255
    private var colorFilter: ColorFilter? = null

    init {
        source.callback = this
        source.setBounds(0, 0, width, height)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || width <= 0 || height <= 0) return
        frameBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        source.draw(frameCanvas)
        paint.alpha = alpha
        paint.colorFilter = colorFilter
        canvas.drawBitmap(frameBitmap, null, bounds, paint)
    }

    override fun start() {
        (source as? Animatable)?.start()
        invalidateSelf()
    }

    override fun stop() {
        (source as? Animatable)?.stop()
        invalidateSelf()
    }

    override fun isRunning(): Boolean = (source as? Animatable)?.isRunning == true

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        this.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = width
    override fun getIntrinsicHeight(): Int = height
}
