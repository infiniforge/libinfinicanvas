package com.infinifolder.preview

import android.graphics.*
import android.graphics.drawable.*
import android.os.SystemClock

/** Main-thread scene drawable; the host retains normal Drawable/Animatable lifecycle ownership. */
internal class OpenRasterPlayback(private val project:OpenRasterDocument):Drawable(),Animatable,Drawable.Callback {
    private data class Player(val animation:BitmapAnimation,val bitmap:Bitmap,val pixels:IntArray)
    private val players=project.assets.filterValues { !it.rasterized }.mapNotNull { (id,a)->
        (a.frame?.animation ?: a.frame?.colorCycling)?.let { id to Player(it,Bitmap.createBitmap(it.width,it.height,Bitmap.Config.ARGB_8888),IntArray(it.width*it.height)) }
    }.toMap()
    private val natives=project.assets.filterValues { !it.rasterized }.mapNotNull { (id,a)->a.frame?.animationDrawable?.let { id to it } }.toMap()
    private var running=false
    private var elapsed=0L
    private var started=0L
    private val tick:Runnable=object:Runnable { override fun run() { if(running) { invalidateSelf();scheduleSelf(this,SystemClock.uptimeMillis()+16) } } }
    override fun start() {
        if(running) return;running=true;started=System.nanoTime()-elapsed
        natives.values.forEach { it.callback=this;(it as? Animatable)?.start() }
        invalidateSelf();scheduleSelf(tick,SystemClock.uptimeMillis()+16)
    }
    override fun stop() {
        if(running) elapsed=System.nanoTime()-started;running=false;unscheduleSelf(tick)
        natives.values.forEach { (it as? Animatable)?.stop();it.callback=null }
    }
    override fun isRunning()=running
    override fun draw(canvas:Canvas) {
        val time=if(running) System.nanoTime()-started else elapsed
        val saved=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width().toFloat()/project.document.width,bounds.height().toFloat()/project.document.height)
        OpenRasterDocuments.draw(project,canvas) { layer,target,paint ->
            val player=players[layer.id];val drawable=natives[layer.id]
            when {
                player!=null -> { do { player.animation.render(time,player.pixels,true) } while(player.animation.needsMoreRendering)
                    player.bitmap.setPixels(player.pixels,0,player.bitmap.width,0,0,player.bitmap.width,player.bitmap.height)
                    target.drawBitmap(player.bitmap,null,RectF(layer.x.toFloat(),layer.y.toFloat(),layer.x.toFloat()+layer.bitmap.width,layer.y.toFloat()+layer.bitmap.height),paint) }
                drawable!=null -> {
                    val save=target.saveLayer(RectF(layer.x.toFloat(),layer.y.toFloat(),layer.x.toFloat()+layer.bitmap.width,layer.y.toFloat()+layer.bitmap.height),paint)
                    drawable.bounds=Rect(layer.x,layer.y,Math.addExact(layer.x,layer.bitmap.width),Math.addExact(layer.y,layer.bitmap.height));drawable.draw(target);target.restoreToCount(save)
                }
                else -> target.drawBitmap(layer.bitmap,layer.x.toFloat(),layer.y.toFloat(),paint)
            }
        }
        canvas.restoreToCount(saved)
    }
    override fun invalidateDrawable(who:Drawable)=invalidateSelf()
    override fun scheduleDrawable(who:Drawable,what:Runnable,`when`:Long)=scheduleSelf(what,`when`)
    override fun unscheduleDrawable(who:Drawable,what:Runnable)=unscheduleSelf(what)
    override fun setAlpha(alpha:Int) {}
    override fun setColorFilter(colorFilter:ColorFilter?) {}
    @Deprecated("Drawable pixel format") override fun getOpacity()=PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth()=project.document.width
    override fun getIntrinsicHeight()=project.document.height
}
