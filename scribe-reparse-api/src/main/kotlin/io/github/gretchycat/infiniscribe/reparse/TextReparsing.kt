package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infiniscribe.TextDocument
import io.github.gretchycat.infiniscribe.TextDocumentHandle
import io.github.gretchycat.infinistream.*
import java.io.IOException
import java.util.Locale

/** Optional structured-text backends produce display documents, never storage mutations. */
object TextReparsing {
    fun descriptor(reparser: TextReparser): ProcessorDescriptor = ProcessorDescriptor(
        "scribe.text.reparse.${reparser.name.lowercase(Locale.ROOT)}",
        listOf(PortDescriptor("text", setOf(DataKind.TEXT), attributes = mapOf("storage" to "text-document"))),
        listOf(PortDescriptor("text", setOf(DataKind.TEXT), attributes = mapOf(
            "storage" to "text-document", "representation" to "reparsed", "reparser" to reparser.name))))

    /** Input and its original bytes are borrowed. Output is reconstructed UTF-8, suitable for display. */
    fun reparse(document: TextDocument, reparser: TextReparser,
        cancellation: CancellationToken = CancellationToken.NONE): TextDocument {
        val processor = TextReparseProcessor(reparser)
        val registry = ProcessorRegistry(listOf(ProcessorRegistration(processor) { inputs, _ ->
            mapOf("text" to listOf(outputMetadata(inputs.getValue("text").single(), reparser)))
        }))
        registry.open(processor.descriptor.id, mapOf("text" to listOf(TextDocumentHandle(document))),
            ProcessorOptions(cancellation = cancellation)).use { session ->
            return (session.outputs.getValue("text").single() as TextDocumentHandle).document
        }
    }

    internal fun outputMetadata(source: StreamMetadata, reparser: TextReparser): StreamMetadata {
        val format = reparser.name.lowercase(Locale.ROOT)
        return source.copy(
            content = ContentType(DataKind.TEXT, "application/$format", format, "unicode"),
            length = null, finiteness = Finiteness.FINITE, capabilities = emptySet(),
            ownership = Ownership.OWNED, concurrency = Concurrency.SERIAL, verification = Verification.UNKNOWN,
            attributes = mapOf("storage" to "text-document", "representation" to "reparsed",
                "reparser" to reparser.name, "complete" to "true", "charset" to "UTF-8",
                "sourceCharset" to (source.attributes["sourceCharset"] ?: source.attributes["charset"] ?: "unknown"),
                "purpose" to "display"),
            provenance = source.provenance.copy(transformations = source.provenance.transformations + descriptor(reparser).id))
    }
}

class TextReparseProcessor(private val reparser: TextReparser) : StreamProcessor {
    override val descriptor = TextReparsing.descriptor(reparser)
    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        if (options.mode != FlowMode.PULL || options.values.isNotEmpty() || inputs.keys != setOf("text"))
            throw IOException("Unsupported reparser input or options")
        options.cancellation.check()
        val source = (inputs["text"]?.singleOrNull() as? TextDocumentHandle)?.document
            ?: throw IOException("One decoded text document is required")
        if (source.hasMore || source.metadata.attributes["complete"] == "false")
            throw IOException("Reparsing requires a complete document")
        val text = reparser.format(source.text, options.cancellation)
        options.cancellation.check()
        var lines = 1L
        text.forEachIndexed { index, char ->
            if (index % 4096 == 0) options.cancellation.check()
            if (char == '\n') lines++
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val metadata = TextReparsing.outputMetadata(source.metadata, reparser).let {
            it.copy(attributes = it.attributes + mapOf("logicalLineCount" to lines.toString(),
                "utf16CodeUnits" to text.length.toString(), "encodedBytes" to bytes.size.toString()))
        }
        val output = TextDocumentHandle(TextDocument(text, bytes, false, metadata))
        return object : ProcessorSession {
            override val outputs = mapOf("text" to listOf<StreamHandle>(output))
            override fun close() = output.close()
        }
    }
}
