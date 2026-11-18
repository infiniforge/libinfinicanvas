package com.infinifolder.preview

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Bounded OpenRaster ZIP and stack.xml reader. Media payloads stay encoded until decoded per layer. */
internal object OpenRasterArchive {
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 128 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    private const val MAX_DOCUMENT_PIXELS = 64_000_000L

    internal data class Layer(
        val src: String,
        val x: Int,
        val y: Int,
        val opacity: Float,
        val visible: Boolean,
        val composite: String,
        val name: String = "Layer"
    )
    internal data class Document(val width: Int, val height: Int, val layers: List<Layer>)

    internal fun readEntries(bytes: ByteArray, retainMedia: Boolean = false, unrestricted:Boolean=false): Map<String, ByteArray>? {
        if (!unrestricted && bytes.size > MAX_INPUT_BYTES) return null
        val found = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val buffer = ByteArray(8192)
                var count = 0
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES && !unrestricted) return null
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith('/') || name.split('/').any { it == ".." }) return null
                    val retain = name == "stack.xml" || name == "mergedimage.png" ||
                        (retainMedia && name.startsWith("data/"))
                    val out = if (retain) ByteArrayOutputStream() else null
                    var itemBytes = 0
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        if (n == 0) throw IOException("OpenRaster ZIP made no progress")
                        expanded += n
                        itemBytes += n
                        if (!unrestricted && (expanded > MAX_EXPANDED_BYTES || itemBytes > MAX_ENTRY_BYTES)) return null
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

    internal fun parseStack(bytes: ByteArray, unrestricted:Boolean=false): Document? {
        if (!unrestricted && bytes.size > 1024 * 1024) return null
        val xml = bytes.toString(Charsets.UTF_8)
        if (Regex("<!\\s*DOCTYPE", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return null
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
                private fun element(localName: String?, qName: String): String =
                    localName?.takeIf { it.isNotBlank() } ?: qName.substringAfter(':')

                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    when (element(localName, qName)) {
                        "image" -> {
                            width = attributes.getValue("w")?.toIntOrNull() ?: 0
                            height = attributes.getValue("h")?.toIntOrNull() ?: 0
                        }
                        "layer" -> {
                            if (current != null) { invalid = true; return }
                            val src = attributes.getValue("src")?.replace('\\', '/') ?: run { invalid = true; return }
                            current = MutableLayer(
                                src = src,
                                x = attributes.getValue("x")?.toIntOrNull() ?: 0,
                                y = attributes.getValue("y")?.toIntOrNull() ?: 0,
                                opacity = attributes.getValue("opacity")?.toFloatOrNull() ?: 1f,
                                visible = attributes.getValue("visibility") != "hidden",
                                composite = attributes.getValue("composite-op") ?: "svg:src-over",
                                name = attributes.getValue("name") ?: "Layer"
                            )
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (element(localName, qName) == "layer" && current != null) {
                        val layer = current ?: return
                        if (!layer.opacity.isFinite() || layer.opacity !in 0f..1f) invalid = true
                        layers += Layer(layer.src, layer.x, layer.y, layer.opacity, layer.visible, layer.composite, layer.name)
                        current = null
                    }
                }
            }
            reader.parse(org.xml.sax.InputSource(ByteArrayInputStream(bytes)))
            if (invalid || width <= 0 || height <= 0 ||
                (!unrestricted && width.toLong() * height > MAX_DOCUMENT_PIXELS) || layers.isEmpty()) null
            else Document(width, height, layers)
        } catch (cancel: java.io.InterruptedIOException) {
            throw cancel
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
        val composite: String,
        val name: String = "Layer"
    )
}
