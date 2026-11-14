package com.infinifolder.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/** Decoder for the InfiniMedia OpenRaster palette-cycle extension. */
internal object OpenRasterPaletteCycle {
    const val NAMESPACE = "https://infinimedia.org/ns/openraster/palette-cycle/1"
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 128 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    private const val MAX_PIXELS = 4_000_000

    internal data class Decoded(val bitmap: Bitmap, val animation: BitmapAnimation)
    internal data class Attempt(val decoded: Decoded?, val diagnostic: String)
    internal data class Range(val first: Int, val last: Int)
    internal data class Cycle(
        val enabled: Boolean,
        val intervalMillis: Double,
        val step: Int,
        val reverse: Boolean,
        val loop: Boolean,
        val transparentIndex: Int?,
        val paletteEntries: Int,
        val ranges: List<Range>
    )
    internal data class Layer(
        val src: String,
        val x: Int,
        val y: Int,
        val opacity: Float,
        val visible: Boolean,
        val composite: String,
        val cycle: Cycle? = null
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
            val cycle = layer.cycle?.let { c ->
                "cycle(enabled=${c.enabled},mode=rotate,intervalMs=${c.intervalMillis},step=${c.step}," +
                    "direction=${if (c.reverse) "reverse" else "forward"},loop=${c.loop}," +
                    "transparentIndex=${c.transparentIndex},paletteEntries=${c.paletteEntries}," +
                    "ranges=${c.ranges.joinToString { "${it.first}-${it.last}" }})"
            } ?: "no-cycle"
            "$index:${layer.src}@${layer.x},${layer.y},visible=${layer.visible}," +
                "opacity=${layer.opacity},composite=${layer.composite},$cycle"
        }.joinToString("|")
        val documentSummary = "canvas=${document.width}x${document.height} layers=$layerSummary"
        if (document.width.toLong() * document.height > MAX_PIXELS) return fallback("canvas-over-pixel-limit", documentSummary)
        val cycleLayers = document.layers.withIndex().filter { it.value.cycle != null }
        if (cycleLayers.size != 1) return fallback("cycle-layer-count-${cycleLayers.size}", documentSummary)
        val (cycleIndex, cycleLayer) = cycleLayers.single()
        val cycle = cycleLayer.cycle ?: return fallback("cycle-metadata-missing", documentSummary)
        if (!cycle.enabled || !cycle.loop || !cycleLayer.visible || cycleLayer.src.isBlank() ||
            document.layers.any { it.visible && it.composite != "svg:src-over" }) {
            return fallback("unsupported-cycle-or-composite-settings", documentSummary)
        }
        val cycleEntry = entries[cycleLayer.src] ?: return fallback("cycle-layer-png-missing", documentSummary)
        val indexed = IndexedPngDecoder.decode(cycleEntry, MAX_PIXELS)
            ?: return fallback("cycle-layer-png-not-supported ${IndexedPngDecoder.headerSummary(cycleEntry)}", documentSummary)
        if (!valid(cycle, indexed.palette.size)) return fallback("palette-ranges-invalid paletteSize=${indexed.paletteSize}", documentSummary)
        cycle.transparentIndex?.let { indexed.palette[it] = indexed.palette[it] and 0x00ffffff }

        val scale = minOf(1f, targetPixels.toFloat() / maxOf(document.width, document.height))
        val width = (document.width * scale).roundToInt().coerceAtLeast(1)
        val height = (document.height * scale).roundToInt().coerceAtLeast(1)
        if (width.toLong() * height > MAX_PIXELS) return fallback("scaled-canvas-over-pixel-limit", documentSummary)
        val lower = document.layers.indices.filter { it > cycleIndex && document.layers[it].visible }
        val upper = (cycleIndex - 1 downTo 0).filter { document.layers[it].visible }
        val beforePixels = compose(document, entries, lower, width, height, scale)
            ?: return fallback("lower-layer-compose-failed", documentSummary)
        val afterPixels = if (upper.isEmpty()) null else compose(document, entries, upper, width, height, scale)
            ?: return fallback("upper-layer-compose-failed", documentSummary)
        val opacity = (cycleLayer.opacity * 255f).roundToInt().coerceIn(0, 255)
        val animation = PaletteAnimation(width, height, beforePixels, afterPixels, cycleLayer,
            indexed, cycle, scale, opacity)
        val pixels = IntArray(width * height)
        animation.render(0L, pixels, true)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            ?: return fallback("bitmap-create-failed", documentSummary)
        val pngSummary = IndexedPngDecoder.headerSummary(cycleEntry)
        return Attempt(Decoded(bitmap, animation),
            "result=animated $documentSummary indexedLayer=${cycleLayer.src} " +
                "indexed=${indexed.width}x${indexed.height} paletteSize=${indexed.paletteSize} png=[$pngSummary]")
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
                    val retain = name == "stack.xml" || name.endsWith(".png", true)
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
        return try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            val reader = factory.newSAXParser().xmlReader
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            var width = 0
            var height = 0
            var invalid = false
            var current: MutableLayer? = null
            var cycleEnabled = true
            var cycleInterval = 60.0
            var cycleStep = 1
            var cycleReverse = false
            var cycleLoop = true
            var cycleTransparent: Int? = null
            var cycleEntries = 256
            var cycleRanges = mutableListOf<Range>()
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
                                src = attributes.getValue("src") ?: run { invalid = true; return },
                                x = attributes.getValue("x")?.toIntOrNull() ?: 0,
                                y = attributes.getValue("y")?.toIntOrNull() ?: 0,
                                opacity = attributes.getValue("opacity")?.toFloatOrNull() ?: 1f,
                                visible = attributes.getValue("visibility") != "hidden",
                                composite = attributes.getValue("composite-op") ?: "svg:src-over"
                            )
                        }
                        "palette-cycle" -> if (uri == NAMESPACE && current != null) {
                            cycleEnabled = attributes.getValue("enabled")?.toBooleanStrictOrNull() ?: true
                            if (attributes.getValue("mode")?.let { it != "rotate" } == true) invalid = true
                            cycleInterval = attributes.getValue("interval-ms")?.toDoubleOrNull() ?: 60.0
                            cycleStep = attributes.getValue("step")?.toIntOrNull() ?: 1
                            cycleReverse = attributes.getValue("direction") == "reverse"
                            if (attributes.getValue("direction")?.let { it !in setOf("forward", "reverse") } == true) invalid = true
                            cycleLoop = attributes.getValue("loop")?.toBooleanStrictOrNull() ?: true
                            cycleTransparent = attributes.getValue("transparent-index")?.toIntOrNull()
                            cycleEntries = attributes.getValue("palette-entry-count")?.toIntOrNull() ?: 256
                            cycleRanges = mutableListOf()
                            if (!cycleInterval.isFinite() || cycleInterval <= 0 || cycleStep <= 0 ||
                                cycleTransparent?.let { it !in 0..255 } == true || cycleEntries !in 1..256) invalid = true
                        }
                        "range" -> if (uri == NAMESPACE && current != null) {
                            val first = attributes.getValue("start")?.toIntOrNull()
                            val last = attributes.getValue("end")?.toIntOrNull()
                            if (first == null || last == null) invalid = true
                            else cycleRanges += Range(first, last)
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String, qName: String) {
                    if (localName == "palette-cycle" && uri == NAMESPACE && current != null) {
                        current?.cycle = Cycle(cycleEnabled, cycleInterval, cycleStep, cycleReverse,
                            cycleLoop, cycleTransparent, cycleEntries, cycleRanges.toList())
                    } else if (localName == "layer" && current != null) {
                        val layer = current ?: return
                        if (!layer.opacity.isFinite() || layer.opacity !in 0f..1f) invalid = true
                        layers += Layer(layer.src, layer.x, layer.y, layer.opacity,
                            layer.visible, layer.composite, layer.cycle)
                        current = null
                    }
                }
            }
            reader.parse(org.xml.sax.InputSource(ByteArrayInputStream(bytes)))
            if (invalid || width <= 0 || height <= 0 || !ImageDecodeUtils.isValidDimensions(width, height) || layers.isEmpty()) null
            else Document(width, height, layers)
        } catch (cancel: java.io.InterruptedIOException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    private data class MutableLayer(
        val src: String, val x: Int, val y: Int, val opacity: Float,
        val visible: Boolean, val composite: String, var cycle: Cycle? = null
    )

    private fun valid(cycle: Cycle, paletteSize: Int): Boolean {
        if (cycle.paletteEntries > paletteSize || cycle.transparentIndex?.let { it >= cycle.paletteEntries } == true || cycle.ranges.isEmpty()) return false
        val used = BooleanArray(cycle.paletteEntries)
        for (range in cycle.ranges) {
            if (range.first < 0 || range.last <= range.first || range.last >= cycle.paletteEntries) return false
            for (i in range.first..range.last) {
                if (i == cycle.transparentIndex || used[i]) return false
                used[i] = true
            }
        }
        return true
    }

    private fun compose(document: Document, entries: Map<String, ByteArray>, indices: List<Int>,
        width: Int, height: Int, scale: Float): IntArray? {
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

    internal class PaletteAnimation(
        override val width: Int,
        override val height: Int,
        private val backdrop: IntArray,
        private val foreground: IntArray?,
        private val layer: Layer,
        private val indexed: IndexedPngDecoder.Image,
        private val cycle: Cycle,
        private val scale: Float,
        private val layerOpacity: Int
    ) : BitmapAnimation {
        override val description = "OpenRaster palette cycle (${cycle.ranges.size} range(s))"
        private var lastOffsets: IntArray? = null
        private val palette = indexed.palette.copyOf()

        override fun render(elapsedNanos: Long, output: IntArray, force: Boolean): Boolean {
            require(output.size >= width * height)
            val ticks = (elapsedNanos.coerceAtLeast(0) / (cycle.intervalMillis * 1_000_000.0)).toLong()
            val offsets = IntArray(cycle.ranges.size) { i ->
                val length = cycle.ranges[i].last - cycle.ranges[i].first + 1
                (((ticks % length) * (cycle.step.toLong() % length)) % length).toInt() * if (cycle.reverse) -1 else 1
            }
            if (!force && lastOffsets?.contentEquals(offsets) == true) return false
            val framePalette = indexed.palette.copyOf()
            cycle.ranges.forEachIndexed { rangeIndex, range ->
                val size = range.last - range.first + 1
                val shift = offsets[rangeIndex]
                for (register in range.first..range.last) {
                    framePalette[register] = palette[range.first + Math.floorMod(register - range.first - shift, size)]
                }
            }
            backdrop.copyInto(output, 0, 0, width * height)
            for (y in 0 until height) {
                val sourceY = ((y + 0.5f) / scale).toInt() - layer.y
                if (sourceY !in 0 until indexed.height) continue
                for (x in 0 until width) {
                    val sourceX = ((x + 0.5f) / scale).toInt() - layer.x
                    if (sourceX !in 0 until indexed.width) continue
                    val source = sourceY * indexed.width + sourceX
                    val color = framePalette[indexed.indices[source].toInt() and 0xff]
                    val alpha = ((color ushr 24) * layerOpacity + 127) / 255
                    if (alpha != 0) {
                        val at = y * width + x
                        output[at] = over(color, alpha, output[at])
                    }
                }
            }
            foreground?.let { foregroundPixels ->
                for (i in 0 until width * height) {
                    val color = foregroundPixels[i]
                    val alpha = color ushr 24
                    if (alpha != 0) output[i] = over(color, alpha, output[i])
                }
            }
            lastOffsets = offsets
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
