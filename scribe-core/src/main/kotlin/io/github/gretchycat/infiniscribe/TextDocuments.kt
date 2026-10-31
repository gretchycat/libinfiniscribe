package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

data class TextDocument(val text: String, val bytes: ByteArray, val hasMore: Boolean, val metadata: StreamMetadata)

object TextDocuments {
    val decodeDescriptor = ProcessorDescriptor("scribe.text.decode",
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES, DataKind.TEXT), capabilities = setOf(Capability.READ))),
        listOf(PortDescriptor("text", setOf(DataKind.TEXT))), options = emptySet())
    val markdownDescriptor = ProcessorDescriptor("scribe.markdown.html",
        listOf(PortDescriptor("markdown", setOf(DataKind.TEXT))),
        listOf(PortDescriptor("html", setOf(DataKind.TEXT), formats = setOf("html"))), options = setOf("dark"))

    /** Borrow input; bounded preview plus one lookahead byte. Retains original bytes for revision checks. */
    fun read(input: ByteSource, maxBytes: Int = 4 * 1024 * 1024, charset: Charset = Charsets.UTF_8,
        cancellation: CancellationToken = CancellationToken.NONE): TextDocument {
        val processor = TextDecodeProcessor(maxBytes, charset)
        val registry = ProcessorRegistry(listOf(ProcessorRegistration(processor) { inputs, _ ->
            val source = inputs.getValue("encoded").single()
            mapOf("text" to listOf(source.copy(
                content = ContentType(DataKind.TEXT, "text/plain", "text", "unicode"),
                length = null, finiteness = Finiteness.FINITE, capabilities = emptySet(), verification = Verification.UNKNOWN,
                ownership = Ownership.OWNED, concurrency = Concurrency.SERIAL,
                provenance = source.provenance.copy(transformations = source.provenance.transformations + "decode:${charset.name()}"),
                attributes = mapOf("charset" to charset.name(), "sourceCharset" to charset.name(), "storage" to "text-document"))))
        }))
        val target = PortDescriptor("text", setOf(DataKind.TEXT), attributes = mapOf("charset" to charset.name(), "storage" to "text-document"))
        registry.openPipeline(input, target, ProcessorOptions(cancellation = cancellation)).use { session ->
            return (session.outputs.getValue("text").single() as TextDocumentHandle).document
        }
    }

    internal fun readBounded(input: ByteSource, maxBytes: Int, charset: Charset,
        cancellation: CancellationToken): TextDocument {
        require(maxBytes in 0..(64 * 1024 * 1024))
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024)); val buffer = ByteArray(64 * 1024)
        while (output.size() < maxBytes) {
            cancellation.check()
            val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (n < 0) break
            if (n == 0) throw IOException("Text input made no progress")
            output.write(buffer, 0, n)
        }
        cancellation.check()
        val hasMore = input.read(buffer, 0, 1) != -1
        val bytes = output.toByteArray(); val text = String(bytes, charset)
        val sourceMetadata = input.metadata
        val metadata = sourceMetadata.copy(content = ContentType(DataKind.TEXT, "text/plain", "text", "unicode"),
            length = null, finiteness = Finiteness.FINITE, capabilities = emptySet(),
            ownership = Ownership.OWNED, concurrency = Concurrency.SERIAL, attributes = mapOf("charset" to charset.name(), "sourceCharset" to charset.name(), "storage" to "text-document", "decoding" to "replace-invalid", "complete" to (!hasMore).toString(), "utf16CodeUnits" to text.length.toString()),
            provenance = sourceMetadata.provenance.copy(transformations = sourceMetadata.provenance.transformations + "decode:${charset.name()}"),
            verification = if (hasMore) Verification.PROVISIONAL else Verification.UNKNOWN,
            warnings = sourceMetadata.warnings + if (hasMore) listOf("Text preview is truncated") else emptyList())
        return TextDocument(text, bytes, hasMore, metadata)
    }
}


/** Typed value output; closing the session never closes its borrowed encoded input. */
class TextDocumentHandle(val document: TextDocument) : StreamHandle {
    override val metadata get() = document.metadata
    override fun close() {}
}

class TextDecodeProcessor(private val maxBytes: Int = 4 * 1024 * 1024,
    private val charset: Charset = Charsets.UTF_8) : StreamProcessor {
    override val descriptor = TextDocuments.decodeDescriptor
    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        if (options.mode != FlowMode.PULL || options.values.isNotEmpty()) throw IOException("Unsupported text processor options")
        val input = inputs["encoded"]?.singleOrNull() as? ByteSource ?: throw IOException("One encoded byte source is required")
        val document = TextDocuments.readBounded(input, maxBytes, charset, options.cancellation)
        val value = TextDocumentHandle(document)
        return object : ProcessorSession {
            override val outputs = mapOf("text" to listOf<StreamHandle>(value))
            override fun close() = value.close()
        }
    }
}
