package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

data class EpubChapter(val id: String, val href: String, val title: String, val path: String)

class EpubBook(val title: String, val author: String, val chapters: List<EpubChapter>,
    override val metadata: StreamMetadata) : StreamHandle {
    override fun close() {} // Immutable description; resources remain borrowed.
}

/** Optional work budgets. Defaults retain existing valid-book size behavior; hosts retain cache limits. */
data class EpubReadLimits(val maxXmlBytes: Long = Long.MAX_VALUE, val maxXmlEvents: Long = Long.MAX_VALUE,
    val maxXmlText: Long = Long.MAX_VALUE, val maxXmlDepth: Int = Int.MAX_VALUE, val maxResourcePaths: Int = Int.MAX_VALUE) {
    init { require(maxXmlBytes > 0 && maxXmlEvents > 0 && maxXmlText > 0 && maxXmlDepth > 0 && maxResourcePaths > 0) }
}

/** EPUB structure over borrowed related resources. ZIP/container interpretation belongs to Vault. */
object EpubDocuments {
    val descriptor = ProcessorDescriptor("scribe.epub.parse",
        listOf(PortDescriptor("package", setOf(DataKind.DIRECTORY), attributes = mapOf("storage" to "document-resources"))),
        listOf(PortDescriptor("book", setOf(DataKind.STRUCTURED), attributes = mapOf("storage" to "epub-book"))))

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun read(resources: DocumentResources, fallbackTitle: String = "EPUB Reader",
        cancellation: CancellationToken = CancellationToken.NONE, limits: EpubReadLimits = EpubReadLimits()): EpubBook {
        val processor = EpubParseProcessor(fallbackTitle, limits)
        val registry = ProcessorRegistry(listOf(ProcessorRegistration(processor) { inputs, _ ->
            mapOf("book" to listOf(bookMetadata(inputs.getValue("package").single())))
        }))
        registry.openPipeline(resources, descriptor.outputs.single(), ProcessorOptions(cancellation = cancellation)).use {
            return it.outputs.getValue("book").single() as EpubBook
        }
    }

    internal fun bookMetadata(input: StreamMetadata) = StreamMetadata(
        ContentType(DataKind.STRUCTURED, "application/epub+zip", "epub-book"), input.name,
        finiteness = Finiteness.FINITE, attributes = mapOf("storage" to "epub-book"),
        provenance = input.provenance.copy(transformations = input.provenance.transformations + "scribe.epub.parse"),
        warnings = input.warnings.toList())

    internal fun parse(resources: DocumentResources, fallbackTitle: String, cancellation: CancellationToken, limits: EpubReadLimits): EpubBook {
        val packagePath = packagePath(resources, cancellation, limits)
        val encrypted = encryptedResources(resources, cancellation, limits)
        val manifest = linkedMapOf<String, String>()
        val spine = mutableListOf<String>()
        var title: String? = null
        var author: String? = null
        var metadataDepth = 0
        var inManifest = false
        var inSpine = false
        var capture: String? = null
        val value = StringBuilder()
        xml(resources, packagePath, cancellation, limits, start = { name, attrs ->
            if (metadataDepth > 0) metadataDepth++
            when (name) {
                "metadata" -> metadataDepth = 1
                "manifest" -> inManifest = true
                "spine" -> inSpine = true
                "item" -> if (inManifest) {
                    val id = attrs["id"]; val href = attrs["href"]
                    if (!id.isNullOrEmpty() && !href.isNullOrEmpty()) manifest[id] = href
                }
                "itemref" -> if (inSpine && attrs["linear"] != "no") attrs["idref"]?.takeIf { it.isNotEmpty() }?.let(spine::add)
            }
            if (metadataDepth > 0 && capture == null && ((name == "title" && title == null) || (name == "creator" && author == null))) {
                capture = name; value.setLength(0)
            }
        }, text = { if (capture != null) value.append(it) }, end = { name ->
            if (name == capture) {
                value.toString().trim().takeIf { it.isNotEmpty() }?.let { if (name == "title") title = it else author = it }
                capture = null
            }
            if (metadataDepth > 0) metadataDepth--
            if (name == "manifest") inManifest = false
            if (name == "spine") inSpine = false
        })
        val warnings = mutableListOf<String>()
        val toc = mutableMapOf<String, String>()
        // Retain the existing NCX title priority; EPUB 3 NAV interpretation remains follow-up.
        manifest.values.firstOrNull { it.endsWith(".ncx", true) }?.let { href ->
            val path = DocumentPaths.resolve(packagePath, href)
            if (path != null && resources.source(path) != null) try {
                var label = ""
                var inText = false
                val text = StringBuilder()
                xml(resources, path, cancellation, limits, start = { name, attrs ->
                    if (name == "text") { inText = true; text.setLength(0) }
                    if (name == "content" && label.isNotEmpty()) attrs["src"]?.let { ref ->
                        DocumentPaths.resolve(path, ref)?.let { toc[it] = label }; label = ""
                    }
                }, text = { if (inText) text.append(it) }, end = { name ->
                    if (name == "text") { label = text.toString().trim(); inText = false }
                })
            } catch (error: Exception) {
                cancellation.check()
                if (error is java.io.InterruptedIOException) throw error
                warnings += "Unable to read NCX titles; chapter-title fallback used"
            }
        }
        val chapters = mutableListOf<EpubChapter>()
        val skippedEncrypted = linkedSetOf<String>()
        fun chapter(id: String, href: String, fallbackNumber: Int) {
            cancellation.check()
            val path = DocumentPaths.resolve(packagePath, href) ?: return
            encrypted[path]?.let { algorithm ->
                if (skippedEncrypted.add(path)) warnings += "Skipped encrypted EPUB chapter ($algorithm)"
                return
            }
            val source = resources.source(path) ?: return
            val chapterTitle = toc[path] ?: htmlTitle(source, cancellation) ?: "Chapter $fallbackNumber"
            chapters += EpubChapter(id, href, chapterTitle, path)
        }
        for (id in spine) manifest[id]?.let { chapter(id, it, chapters.size + 1) }
        if (chapters.isEmpty() && skippedEncrypted.isEmpty()) manifest.values.filter {
            it.endsWith(".html", true) || it.endsWith(".xhtml", true) || it.endsWith(".htm", true)
        }.forEachIndexed { index, href -> chapter("item_$index", href, index + 1) }
        if (chapters.isEmpty() && skippedEncrypted.isNotEmpty()) {
            val algorithms = skippedEncrypted.mapNotNull { encrypted[it] }.distinct().joinToString(", ")
            throw IOException("This EPUB's chapters are encrypted ($algorithms). This reader cannot decrypt them; a stylesheet cannot make them readable.")
        }
        val info = bookMetadata(resources.metadata).let { it.copy(attributes = it.attributes + mapOf(
            "title" to (title ?: fallbackTitle), "author" to (author ?: ""), "chapterCount" to chapters.size.toString(),
            "packagePath" to packagePath), warnings = it.warnings + warnings) }
        return EpubBook(title ?: fallbackTitle, author ?: "", chapters.toList(), info)
    }

    private fun encryptedResources(resources: DocumentResources, cancellation: CancellationToken,
        limits: EpubReadLimits): Map<String, String> {
        if (resources.source("META-INF/encryption.xml") == null) return emptyMap()
        val encrypted = linkedMapOf<String, String>()
        var algorithm = ""
        var inData = false
        val references = mutableListOf<String>()
        xml(resources, "META-INF/encryption.xml", cancellation, limits, start = { name, attrs ->
            if (name == "encrypteddata") { inData = true; algorithm = ""; references.clear() }
            if (inData && name == "encryptionmethod" && algorithm.isEmpty())
                algorithm = attrs["Algorithm"] ?: attrs["algorithm"].orEmpty()
            if (inData && name == "cipherreference") (attrs["URI"] ?: attrs["uri"])?.let {
                // CipherReference paths are relative to the EPUB root, not META-INF.
                DocumentPaths.resolve("encryption.xml", it)?.let(references::add)
            }
        }, end = { name ->
            if (name == "encrypteddata") {
                references.forEach { encrypted[it] = algorithm.ifBlank { "unspecified encryption" } }
                inData = false
            }
        })
        return encrypted
    }

    internal fun packagePath(resources: DocumentResources, cancellation: CancellationToken, limits: EpubReadLimits): String {
        var opfPath: String? = null
        xml(resources, "META-INF/container.xml", cancellation, limits, start = { name, attrs ->
            if (name == "rootfile" && opfPath == null) attrs["full-path"]?.takeIf { it.isNotEmpty() }?.let {
                opfPath = DocumentPaths.resolve("", it) ?: throw IOException("Invalid EPUB package path")
            }
        })
        if (opfPath == null) opfPath = resources.paths().withIndex().firstOrNull {
            cancellation.check()
            if (it.index >= limits.maxResourcePaths) throw IOException("Too many EPUB resources")
            it.value.endsWith(".opf", true)
        }?.value
        return opfPath ?: throw IOException("Package metadata (OPF) not found")
    }

    private fun htmlTitle(source: SourceResource, cancellation: CancellationToken): String? {
        return try {
            cancellation.check()
            val header = source.open().asInputStream().reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(2048)
                var count = 0
                while (count < buffer.size) {
                    cancellation.check()
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) throw IOException("EPUB title source made no progress")
                    count += read
                }
                String(buffer, 0, count)
            }
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()
            if (!title.isNullOrEmpty() && !title.equals("untitled", true)) title else
                Regex("<h[1-2][^>]*>(.*?)</h[1-2]>", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]*>"), "")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            cancellation.check()
            if (error is java.io.InterruptedIOException) throw error
            null
        }
    }

    internal fun xml(resources: DocumentResources, path: String, cancellation: CancellationToken, limits: EpubReadLimits,
        start: (String, Map<String, String>) -> Unit = { _, _ -> }, text: (String) -> Unit = {}, end: (String) -> Unit = {}) {
        val source = resources.source(path) ?: throw IOException("Invalid EPUB: $path not found")
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        // EntityResolver is authoritative even where an optional feature is unavailable.
        for (feature in listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
            try { reader.setFeature(feature, false) } catch (_: SAXException) {}
        }
        var depth = 0; var events = 0L; var chars = 0L
        val handler = object : DefaultHandler2() {
            fun checkEvent() {
                cancellation.check()
                if (++events > limits.maxXmlEvents) throw IOException("EPUB XML event budget exceeded")
            }
            override fun startElement(uri: String?, local: String?, qName: String?, attrs: Attributes) {
                checkEvent()
                if (depth >= limits.maxXmlDepth) throw IOException("EPUB XML nesting budget exceeded")
                depth++
                val name = (local?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')).lowercase(Locale.ROOT)
                start(name, (0 until attrs.length).associate { attrs.getQName(it).substringAfter(':') to attrs.getValue(it) })
            }
            override fun characters(ch: CharArray, offset: Int, length: Int) {
                checkEvent(); chars += length
                if (chars > limits.maxXmlText) throw IOException("EPUB XML text budget exceeded")
                text(String(ch, offset, length))
            }
            override fun endElement(uri: String?, local: String?, qName: String?) {
                checkEvent(); depth--
                end((local?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')).lowercase(Locale.ROOT))
            }
            override fun startEntity(name: String?) {
                if (name != null && name !in setOf("[dtd]", "amp", "lt", "gt", "apos", "quot"))
                    throw SAXException("EPUB XML custom entities are unsupported")
            }
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                if (publicId == null && systemId == null) throw SAXException("EPUB XML internal DTDs are unsupported")
            }
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(ByteArrayInputStream(byteArrayOf()))
            override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?) = resolveEntity(publicId, systemId)
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        }
        reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        source.open().asInputStream().use { input ->
            reader.parse(InputSource(EpubXmlInput(input, limits.maxXmlBytes, cancellation)))
        }
    }
}

class EpubParseProcessor(private val fallbackTitle: String = "EPUB Reader", private val limits: EpubReadLimits = EpubReadLimits()) : StreamProcessor {
    override val descriptor = EpubDocuments.descriptor
    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        if (options.mode != FlowMode.PULL || options.values.isNotEmpty()) throw IOException("Unsupported EPUB processor options")
        val resources = inputs["package"]?.singleOrNull() as? DocumentResources ?: throw IOException("One document resource package is required")
        val book = EpubDocuments.parse(resources, fallbackTitle, options.cancellation, limits)
        return object : ProcessorSession {
            override val outputs = mapOf("book" to listOf<StreamHandle>(book))
            override fun close() = book.close()
        }
    }
}
