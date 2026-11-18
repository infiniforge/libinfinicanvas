package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import java.io.InterruptedIOException
import kotlin.math.roundToInt

/** Plays supported visual media referenced by an OpenRaster stack layer. */
internal object OpenRasterAnimationDecoder {
    private const val MAX_LAYERS = 256
    private const val MAX_LAYER_PIXELS = 4_000_000L
    private const val MAX_TOTAL_LAYER_PIXELS = 16_000_000L
    private const val MAX_LAYER_OFFSET = 1_000_000
    private const val MAX_NESTING = 4
    private val nesting = ThreadLocal.withInitial { 0 }

    internal data class Decoded(val bitmap: Bitmap, val drawable: Drawable, val durationNanos: Long,
        val diagnostics: String)

    internal data class Layer(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val opacity: Int,
        val bitmap: Bitmap?,
        val pixels: IntArray?,
        val animation: BitmapAnimation?,
        val drawable: Drawable?
    )

    fun decode(bytes: ByteArray, targetPixels: Int): Decoded? {
        val depth = nesting.get() ?: 0
        if (depth >= MAX_NESTING) return null
        nesting.set(depth + 1)
        try {
            return decodeInternal(bytes, targetPixels)
        } catch (cancel: InterruptedIOException) {
            throw cancel
        } catch (_: Exception) {
            return null
        } finally {
            nesting.set(depth)
        }
    }

    private fun decodeInternal(bytes: ByteArray, targetPixels: Int): Decoded? {
        if (targetPixels !in 1..4096) return null
        val entries = OpenRasterArchive.readEntries(bytes, retainMedia = true) ?: return null
        val xml = entries["stack.xml"] ?: return null
        val document = OpenRasterArchive.parseStack(xml) ?: return null
        if (document.layers.size !in 1..MAX_LAYERS ||
            document.width.toLong() * document.height > MAX_LAYER_PIXELS ||
            document.layers.any { it.visible && it.composite != "svg:src-over" }) return null

        val layerTarget = minOf(4096, maxOf(targetPixels, maxOf(document.width, document.height)))
        val prepared = ArrayList<Layer>(document.layers.size)
        var animated = false
        var decodedLayerPixels = 0L
        var ownershipTransferred = false
        try {
            for (source in document.layers) {
                if (!source.visible) continue
                if (!safeSource(source.src) || source.x !in -MAX_LAYER_OFFSET..MAX_LAYER_OFFSET ||
                    source.y !in -MAX_LAYER_OFFSET..MAX_LAYER_OFFSET) return null
                val encoded = entries[source.src] ?: return null
                val frame = CanvasStreams.decodeBytes(encoded, source.src, layerTarget, true) ?: return null
                val animation = frame.animation ?: frame.colorCycling
                val childDrawable = frame.animationDrawable?.takeIf { it is Animatable }
                val bitmap: Bitmap?
                val pixels: IntArray?
                val width: Int
                val height: Int
                if (animation != null) {
                    width = animation.width
                    height = animation.height
                    val area = width.toLong() * height
                    if (width <= 0 || height <= 0 || area > MAX_LAYER_PIXELS ||
                        decodedLayerPixels + area > MAX_TOTAL_LAYER_PIXELS) {
                        frame.bitmap.recycle()
                        return null
                    }
                    decodedLayerPixels += area
                    pixels = IntArray(width * height)
                    var passes = 0
                    do {
                        animation.render(0L, pixels, true)
                        passes++
                    } while (animation.needsMoreRendering && passes < 8)
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    animated = true
                } else if (childDrawable != null) {
                    width = frame.bitmap.width
                    height = frame.bitmap.height
                    val area = width.toLong() * height
                    if (width <= 0 || height <= 0 || area > MAX_LAYER_PIXELS ||
                        decodedLayerPixels + area > MAX_TOTAL_LAYER_PIXELS) {
                        frame.bitmap.recycle()
                        return null
                    }
                    decodedLayerPixels += area
                    bitmap = null
                    pixels = null
                    animated = true
                } else {
                    width = frame.bitmap.width
                    height = frame.bitmap.height
                    val area = width.toLong() * height
                    if (width <= 0 || height <= 0 || area > MAX_LAYER_PIXELS ||
                        decodedLayerPixels + area > MAX_TOTAL_LAYER_PIXELS) {
                        frame.bitmap.recycle()
                        return null
                    }
                    decodedLayerPixels += area
                    bitmap = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    pixels = null
                    if (bitmap == null) {
                        frame.bitmap.recycle()
                        return null
                    }
                }
                if (frame.bitmap !== bitmap && !frame.bitmap.isRecycled) frame.bitmap.recycle()
                prepared += Layer(source.x, source.y, width, height,
                    (source.opacity * 255f).roundToInt().coerceIn(0, 255), bitmap, pixels,
                    animation, childDrawable)
            }
            if (!animated || prepared.isEmpty()) return null

            val poster = decodePoster(entries["mergedimage.png"], targetPixels)
                ?: renderPoster(document.width, document.height, targetPixels, prepared)
                ?: return null
            val drawable = OpenRasterAnimationDrawable(document.width, document.height, prepared)
            val duration = drawable.durationNanos
            val summary = "result=animated ora=${document.width}x${document.height} " +
                "layers=${prepared.size} animatedLayers=${prepared.count { it.animation != null || it.drawable is Animatable }}"
            ownershipTransferred = true
            return Decoded(poster, drawable, duration, summary)
        } finally {
            if (!ownershipTransferred) releaseLayers(prepared)
        }
    }

    private fun releaseLayers(layers: List<Layer>) {
        layers.forEach { layer ->
            layer.drawable?.callback = null
            (layer.drawable as? Animatable)?.stop()
            layer.bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun safeSource(source: String): Boolean = source.isNotBlank() &&
        !source.startsWith('/') && source.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun decodePoster(bytes: ByteArray?, targetPixels: Int): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (!ImageDecodeUtils.isValidDimensions(bounds.outWidth, bounds.outHeight)) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageDecodeUtils.sampleSize(bounds.outWidth, bounds.outHeight, targetPixels, targetPixels)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: PureJavaPngFallback.decode(bytes, targetPixels)
    }

    private fun renderPoster(docWidth: Int, docHeight: Int, targetPixels: Int, layers: List<Layer>): Bitmap? {
        val scale = minOf(1.0, targetPixels.toDouble() / maxOf(docWidth, docHeight))
        val width = maxOf(1, (docWidth * scale).toInt())
        val height = maxOf(1, (docHeight * scale).toInt())
        if (width.toLong() * height > MAX_LAYER_PIXELS) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = OpenRasterAnimationDrawable(docWidth, docHeight, layers)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}

/** Draws stack layers in document order and forwards native animation scheduling. */
private class OpenRasterAnimationDrawable(
    private val documentWidth: Int,
    private val documentHeight: Int,
    private val layers: List<OpenRasterAnimationDecoder.Layer>
) : Drawable(), Animatable, Drawable.Callback {
    private var parentAlpha = 255
    private var colorFilter: ColorFilter? = null
    private var running = false
    private var startedAtNanos = 0L
    private var pausedElapsedNanos = 0L
    private var tickScheduled = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tick = Runnable {
        tickScheduled = false
        invalidateSelf()
        scheduleTick()
    }
    val durationNanos: Long = run {
        val durations = layers.filter { it.animation != null || it.drawable is Animatable }
            .map { it.animation?.durationNanos ?: 0L }
        if (durations.isEmpty() || durations.any { it <= 0L }) 0L else durations.maxOrNull() ?: 0L
    }

    init {
        layers.forEach { it.drawable?.callback = this }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || documentWidth <= 0 || documentHeight <= 0) return
        val save = canvas.save()
        canvas.clipRect(bounds)
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width().toFloat() / documentWidth, bounds.height().toFloat() / documentHeight)
        val elapsed = elapsedNanos()
        for (layer in layers.asReversed()) drawLayer(canvas, layer, elapsed)
        canvas.restoreToCount(save)
        if (running && layers.any { it.animation != null }) scheduleTick()
    }

    private fun drawLayer(canvas: Canvas, layer: OpenRasterAnimationDecoder.Layer, elapsed: Long) {
        if (layer.opacity == 0) return
        val right = layer.x + layer.width
        val bottom = layer.y + layer.height
        val alpha = (layer.opacity * parentAlpha + 127) / 255
        val layerSave = if (alpha < 255) canvas.saveLayerAlpha(
            RectF(layer.x.toFloat(), layer.y.toFloat(), right.toFloat(), bottom.toFloat()), alpha
        ) else -1

        try {
            val drawable = layer.drawable
            if (drawable != null) {
                val old = android.graphics.Rect(drawable.bounds)
                drawable.setBounds(layer.x, layer.y, right, bottom)
                drawable.draw(canvas)
                drawable.bounds = old
            } else {
                val bitmap = layer.bitmap ?: return
                layer.animation?.let { animation ->
                    val pixels = layer.pixels ?: return
                    var passes = 0
                    do {
                        animation.render(elapsed, pixels, passes == 0)
                        passes++
                    } while (animation.needsMoreRendering && passes < 8)
                    bitmap.setPixels(pixels, 0, layer.width, 0, 0, layer.width, layer.height)
                }
                paint.alpha = if (layerSave >= 0) 255 else alpha
                paint.colorFilter = colorFilter
                canvas.drawBitmap(bitmap, null, RectF(layer.x.toFloat(), layer.y.toFloat(), right.toFloat(), bottom.toFloat()), paint)
                paint.alpha = 255
                paint.colorFilter = null
            }
        } finally {
            if (layerSave >= 0) canvas.restoreToCount(layerSave)
        }
    }

    private fun elapsedNanos(): Long = if (running)
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(0L)
    else pausedElapsedNanos

    override fun start() {
        if (running) return
        running = true
        startedAtNanos = SystemClock.elapsedRealtimeNanos() - pausedElapsedNanos
        layers.mapNotNull { it.drawable as? Animatable }.forEach { it.start() }
        invalidateSelf()
        scheduleTick()
    }

    override fun stop() {
        if (!running) return
        pausedElapsedNanos = elapsedNanos()
        running = false
        unscheduleSelf(tick)
        tickScheduled = false
        layers.mapNotNull { it.drawable as? Animatable }.forEach { it.stop() }
        invalidateSelf()
    }

    override fun isRunning(): Boolean = running

    private fun scheduleTick() {
        if (!running || tickScheduled || layers.none { it.animation != null }) return
        tickScheduled = true
        scheduleSelf(tick, SystemClock.uptimeMillis() + 33L)
    }

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    override fun setAlpha(alpha: Int) { parentAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { this.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = documentWidth
    override fun getIntrinsicHeight(): Int = documentHeight

}
