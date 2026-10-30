package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

data class TextDocument(val text: String, val bytes: ByteArray, val hasMore: Boolean, val metadata: StreamMetadata)

object TextDocuments {
    val decodeDescriptor = ProcessorDescriptor("scribe.text.decode",
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES), capabilities = setOf(Capability.READ))),
        listOf(PortDescriptor("text", setOf(DataKind.TEXT))), options = setOf("charset", "maxBytes"))
    val markdownDescriptor = ProcessorDescriptor("scribe.markdown.html",
        listOf(PortDescriptor("markdown", setOf(DataKind.TEXT))),
        listOf(PortDescriptor("html", setOf(DataKind.TEXT), formats = setOf("html"))), options = setOf("dark"))

    /** Borrow input; bounded preview plus one lookahead byte. Retains original bytes for revision checks. */
    fun read(input: ByteSource, maxBytes: Int = 4 * 1024 * 1024, charset: Charset = Charsets.UTF_8,
        cancellation: CancellationToken = CancellationToken.NONE): TextDocument {
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
        val metadata = input.metadata.copy(content = ContentType(DataKind.TEXT, "text/plain", "text", charset.name()),
            length = null, capabilities = emptySet(), attributes = mapOf("charset" to charset.name(), "complete" to (!hasMore).toString(), "characters" to text.length.toString()),
            provenance = input.metadata.provenance.copy(transformations = input.metadata.provenance.transformations + "decode:${charset.name()}"),
            verification = if (hasMore) Verification.PROVISIONAL else input.metadata.verification,
            warnings = input.metadata.warnings + if (hasMore) listOf("Text preview is truncated") else emptyList())
        return TextDocument(text, bytes, hasMore, metadata)
    }
}
