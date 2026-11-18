package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToInt
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Decodes OpenRaster documents with a visible, cycling IFF layer. */
internal object OpenRasterIffCycle {
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 128 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    private const val MAX_PIXELS = 4_000_000

    internal data class Decoded(val bitmap: Bitmap, val animation: BitmapAnimation)
    internal data class Attempt(val decoded: Decoded?, val diagnostic: String)
    internal data class Layer(
        val src: String,
        val x: Int,
        val y: Int,
        val opacity: Float,
        val visible: Boolean,
        val composite: String
    )
    internal data class Document(val width: Int, val height: Int, val layers: List<Layer>)

    fun decode(bytes: ByteArray, targetPixels: Int): Decoded? = decodeWithDiagnostics(bytes, targetPixels).decoded

    fun decodeWithDiagnostics(bytes: ByteArray, targetPixels: Int): Attempt {
        var xml: ByteArray? = null
        fun fallback(reason: String, details: String = ""): Attempt {
            val stack = xml?.toString(Charsets.UTF_8)?.replace(Regex("\\s+"), " ")?.take(1400)
            val tail = listOf(details.takeIf(String::isNotBlank), stack?.let { "stack.xml=$it" })
                .filterNotNull().joinToString("; ")
            return Attempt(null, "result=static-fallback reason=$reason inputBytes=${bytes.size}" +
                if (tail.isEmpty()) "" else "; $tail")
        }
        if (bytes.size > MAX_INPUT_BYTES) return fallback("input-over-limit-$MAX_INPUT_BYTES")
        if (targetPixels <= 0) return fallback("invalid-target-pixels-$targetPixels")
        val entries = readEntries(bytes) ?: return fallback("zip-rejected-or-resource-limit")
        xml = entries["stack.xml"]
        if (xml == null) return fallback("stack.xml-missing")
        val document = parseStack(xml) ?: return fallback("stack.xml-parse-rejected")
        val layerSummary = document.layers.mapIndexed { index, layer ->
            "$index:${layer.src}@${layer.x},${layer.y},visible=${layer.visible}," +
                "opacity=${layer.opacity},composite=${layer.composite}"
        }.joinToString("|")
        val documentSummary = "canvas=${document.width}x${document.height} layers=$layerSummary"
        if (document.width.toLong() * document.height > MAX_PIXELS)
            return fallback("canvas-over-pixel-limit", documentSummary)

        val embedded = findEmbeddedIffCycle(document, entries, targetPixels)
            ?: return fallback("visible-cycling-iff-layer-not-found", documentSummary)
        if (document.layers.any { it.visible && it.composite != "svg:src-over" })
            return fallback("unsupported-iff-layer-composite", documentSummary)

        val layer = document.layers[embedded.layerIndex]
        val scale = minOf(1f, targetPixels.toFloat() / maxOf(document.width, document.height))
        val width = (document.width * scale).roundToInt().coerceAtLeast(1)
        val height = (document.height * scale).roundToInt().coerceAtLeast(1)
        if (width.toLong() * height > MAX_PIXELS)
            return fallback("scaled-canvas-over-pixel-limit", documentSummary)
        val staticLayers = document.layers.indices.filter { it != embedded.layerIndex && document.layers[it].visible }
        val backdrop = compose(document, entries, staticLayers, width, height, scale)
            ?: return fallback("static-layer-compose-failed", documentSummary)

        val animation = IffLayerAnimation(width, height, backdrop, layer, scale,
            (layer.opacity * 255f).roundToInt().coerceIn(0, 255), embedded.animation)
        val pixels = IntArray(width * height)
        animation.render(0L, pixels, true)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            ?: return fallback("bitmap-create-failed", documentSummary)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return Attempt(Decoded(bitmap, animation),
            "result=animated $documentSummary sourceIFF=${embedded.source} iffLayer=${layer.src} " +
                "nativeSize=${embedded.animation.sourceWidth}x${embedded.animation.sourceHeight} " +
                "nativeRanges=${embedded.animation.rangeCount}")
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray>? {
        val found = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(8192)
                var count = 0
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) return null
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith('/') || name.split('/').any { it == ".." }) return null
                    val retain = name == "stack.xml" || name.endsWith(".png", true) || name.startsWith("data/")
                    val out = if (retain) ByteArrayOutputStream() else null
                    var itemBytes = 0
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        if (n == 0) throw IOException("OpenRaster ZIP made no progress")
                        expanded += n
                        itemBytes += n
                        if (expanded > MAX_EXPANDED_BYTES || itemBytes > MAX_ENTRY_BYTES) return null
                        out?.write(buffer, 0, n)
                    }
                    if (out != null) found[name] = out.toByteArray()
                    zip.closeEntry()
                }
            }
            found.takeIf { it.containsKey("stack.xml") }
        } catch (cancel: java.io.InterruptedIOException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseStack(bytes: ByteArray): Document? {
        if (bytes.size > 1024 * 1024) return null
        if (Regex("<!\\s*DOCTYPE", RegexOption.IGNORE_CASE).containsMatchIn(bytes.toString(Charsets.UTF_8))) return null
        return try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            runCatching { factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            val reader = factory.newSAXParser().xmlReader
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false
            ).forEach { (feature, enabled) -> runCatching { reader.setFeature(feature, enabled) } }
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ ->
                throw org.xml.sax.SAXException("External entities are not allowed in OpenRaster metadata")
            }
            var width = 0
            var height = 0
            var invalid = false
            var current: MutableLayer? = null
            val layers = mutableListOf<Layer>()
            reader.contentHandler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    when (localName) {
                        "image" -> {
                            width = attributes.getValue("w")?.toIntOrNull() ?: 0
                            height = attributes.getValue("h")?.toIntOrNull() ?: 0
                        }
                        "layer" -> {
                            if (current != null) { invalid = true; return }
                            current = MutableLayer(
                                src = attributes.getValue("src")?.replace('\\', '/') ?: run { invalid = true; return },
                                x = attributes.getValue("x")?.toIntOrNull() ?: 0,
                                y = attributes.getValue("y")?.toIntOrNull() ?: 0,
                                opacity = attributes.getValue("opacity")?.toFloatOrNull() ?: 1f,
                                visible = attributes.getValue("visibility") != "hidden",
                                composite = attributes.getValue("composite-op") ?: "svg:src-over"
                            )
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String, qName: String) {
                    if (localName == "layer" && current != null) {
                        val layer = current ?: return
                        if (!layer.opacity.isFinite() || layer.opacity !in 0f..1f) invalid = true
                        layers += Layer(layer.src, layer.x, layer.y, layer.opacity, layer.visible, layer.composite)
                        current = null
                    }
                }
            }
            reader.parse(org.xml.sax.InputSource(ByteArrayInputStream(bytes)))
            if (invalid || current != null || width <= 0 || height <= 0 || layers.isEmpty()) null
            else Document(width, height, layers)
        } catch (_: Exception) {
            null
        }
    }

    private data class MutableLayer(
        val src: String,
        val x: Int,
        val y: Int,
        val opacity: Float,
        val visible: Boolean,
        val composite: String
    )

    private data class EmbeddedIffCycle(
        val source: String,
        val layerIndex: Int,
        val animation: IffColorCycling.Animation
    )

    private fun findEmbeddedIffCycle(
        document: Document,
        entries: Map<String, ByteArray>,
        targetPixels: Int
    ): EmbeddedIffCycle? {
        val candidates = document.layers.withIndex().filter { (_, layer) ->
            layer.visible && isIffPath(layer.src) && entries.containsKey(layer.src)
        }
        val matches = mutableListOf<EmbeddedIffCycle>()
        for ((layerIndex, layer) in candidates) {
            if (Thread.currentThread().isInterrupted)
                throw java.io.InterruptedIOException("OpenRaster IFF cycling cancelled")
            val iff = try {
                IffColorCycling.decode(entries[layer.src] ?: continue, targetPixels)
            } catch (error: Exception) {
                if (error is java.io.InterruptedIOException) throw error
                null
            } ?: continue
            val animation = iff.animation
            iff.bitmap.recycle()
            if (animation != null && animation.rangeCount > 0)
                matches += EmbeddedIffCycle(layer.src, layerIndex, animation)
        }
        val match = matches.singleOrNull() ?: return null
        if (hasOtherAnimatedLayers(document, entries, match.layerIndex, targetPixels)) return null
        return match
    }

    private fun hasOtherAnimatedLayers(
        document: Document,
        entries: Map<String, ByteArray>,
        iffLayerIndex: Int,
        targetPixels: Int
    ): Boolean {
        val animatedExtensions = setOf("fli", "flc", "flic", "mng", "gif", "webp", "avif", "avifs",
            "heif", "heic", "heics", "heifs", "apng")
        for ((index, layer) in document.layers.withIndex()) {
            if (index == iffLayerIndex || !layer.visible) continue
            val extension = layer.src.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if (extension in animatedExtensions) return true
            if (extension == "png") {
                val encoded = entries[layer.src] ?: continue
                val decoded = try { PngAnimationDecoder.decode(encoded, targetPixels) }
                    catch (error: Exception) {
                        if (error is java.io.InterruptedIOException) throw error
                        null
                    }
                val animated = decoded?.animation != null
                decoded?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                if (animated) return true
            }
        }
        return false
    }

    private fun isIffPath(name: String): Boolean =
        name.endsWith(".iff", true) || name.endsWith(".ilbm", true) || name.endsWith(".lbm", true)

    private fun compose(
        document: Document,
        entries: Map<String, ByteArray>,
        indices: List<Int>,
        width: Int,
        height: Int,
        scale: Float
    ): IntArray? {
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) ?: return null
        val canvas = Canvas(target)
        canvas.scale(scale, scale)
        for (index in indices) {
            val layer = document.layers[index]
            val encoded = entries[layer.src] ?: run { target.recycle(); return null }
            val image = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                ?: PureJavaPngFallback.decode(encoded, maxOf(document.width, document.height))
                ?: run { target.recycle(); return null }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (layer.opacity * 255).roundToInt() }
            canvas.drawBitmap(image, layer.x.toFloat(), layer.y.toFloat(), paint)
            image.recycle()
        }
        val pixels = IntArray(width * height)
        target.getPixels(pixels, 0, width, 0, 0, width, height)
        target.recycle()
        return pixels
    }

    /** Composites an indexed IFF layer over the static ORA layers while its native palette advances. */
    internal class IffLayerAnimation(
        override val width: Int,
        override val height: Int,
        private val backdrop: IntArray,
        private val layer: Layer,
        private val scale: Float,
        private val layerOpacity: Int,
        private val iff: IffColorCycling.Animation
    ) : BitmapAnimation {
        override val description = "OpenRaster IFF layer (${iff.rangeCount} cycling range(s))"
        private val iffWidth = (iff.sourceWidth * scale).roundToInt().coerceAtLeast(1)
        private val iffHeight = (iff.sourceHeight * scale).roundToInt().coerceAtLeast(1)
        private val sourcePixels = IntArray(iffWidth * iffHeight)
        private val offsetX = (layer.x * scale).roundToInt()
        private val offsetY = (layer.y * scale).roundToInt()

        override fun render(elapsedNanos: Long, output: IntArray, force: Boolean): Boolean {
            require(output.size >= width * height)
            if (!iff.renderScaled(elapsedNanos, iffWidth, iffHeight, sourcePixels, force)) return false
            backdrop.copyInto(output, 0, 0, width * height)
            for (sourceY in 0 until iffHeight) {
                val y = offsetY + sourceY
                if (y !in 0 until height) continue
                for (sourceX in 0 until iffWidth) {
                    val x = offsetX + sourceX
                    if (x !in 0 until width) continue
                    val color = sourcePixels[sourceY * iffWidth + sourceX]
                    val alpha = ((color ushr 24) * layerOpacity + 127) / 255
                    if (alpha != 0) {
                        val at = y * width + x
                        output[at] = over(color, alpha, output[at])
                    }
                }
            }
            return true
        }

        private fun over(source: Int, alpha: Int, destination: Int): Int {
            val inverse = 255 - alpha
            val da = destination ushr 24
            val outA = alpha + (da * inverse + 127) / 255
            if (outA == 0) return 0
            fun channel(shift: Int): Int {
                val s = source ushr shift and 255
                val d = destination ushr shift and 255
                val premultiplied = s * alpha + (d * da * inverse + 127) / 255
                return ((premultiplied + outA / 2) / outA).coerceIn(0, 255)
            }
            return (outA shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
