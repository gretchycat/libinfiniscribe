package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class TextReparsingTest {
    private val parser = object : TextReparser {
        override val name = "TEST"
        override fun format(text: String, cancellation: CancellationToken) = "$text\nnext"
    }
    private fun document() = TextDocument("original", "original".toByteArray(), false,
        StreamMetadata(content = ContentType(DataKind.TEXT, "text/plain", "text", "unicode"), length = 999,
            capabilities = setOf(Capability.READ, Capability.POSITIONAL_READ), verification = Verification.VERIFIED,
            provenance = Provenance(source = "content://source", checksums = mapOf("sha256" to "source-hash")),
            attributes = mapOf("storage" to "text-document", "complete" to "true", "sourceCharset" to "UTF-16LE", "sourceOffset" to "42")))

    @Test fun transformedMetadataAndOriginalBytesRemainDistinct() {
        val source = document()
        val result = TextReparsing.reparse(source, parser)
        assertEquals("original\nnext", result.text)
        assertArrayEquals(result.text.toByteArray(Charsets.UTF_8), result.bytes)
        assertEquals("original", source.text)
        assertArrayEquals("original".toByteArray(), source.bytes)
        assertEquals("2", result.metadata.attributes["logicalLineCount"])
        assertEquals("13", result.metadata.attributes["encodedBytes"])
        assertEquals("UTF-8", result.metadata.attributes["charset"])
        assertEquals("UTF-16LE", result.metadata.attributes["sourceCharset"])
        assertNull(result.metadata.length)
        assertTrue(result.metadata.capabilities.isEmpty())
        assertEquals(Verification.UNKNOWN, result.metadata.verification)
        assertFalse(result.metadata.attributes.containsKey("sourceOffset"))
        assertEquals(source.metadata.provenance.checksums, result.metadata.provenance.checksums)
        assertEquals(listOf("scribe.text.reparse.test"), result.metadata.provenance.transformations)
        assertEquals("display", result.metadata.attributes["purpose"])
    }
    @Test fun incompleteDocumentsAreRejected() {
        assertThrows(IOException::class.java) { TextReparsing.reparse(document().copy(hasMore = true), parser) }
        val source = document()
        assertThrows(IOException::class.java) {
            TextReparsing.reparse(source.copy(metadata = source.metadata.copy(attributes = source.metadata.attributes + ("complete" to "false"))), parser)
        }
    }
    @Test fun alreadyReparsedDocumentsStillRunTheProcessor() {
        val first = TextReparsing.reparse(document(), parser)
        val next = TextReparsing.reparse(first, parser)
        assertEquals("original\nnext\nnext", next.text)
        assertEquals("3", next.metadata.attributes["logicalLineCount"])
    }
    @Test fun cancellationAndParserFailuresPropagate() {
        assertThrows(InterruptedIOException::class.java) {
            TextReparsing.reparse(document(), parser, CancellationToken { true })
        }
        val failing = object : TextReparser {
            override val name = "BAD"
            override fun format(text: String, cancellation: CancellationToken): String = throw IOException("bad input")
        }
        assertEquals("bad input", assertThrows(IOException::class.java) { TextReparsing.reparse(document(), failing) }.message)
    }
    @Test fun unsupportedFlowAndOptionsAreRejected() {
        val processor = TextReparseProcessor(parser)
        val inputs = mapOf("text" to listOf<StreamHandle>(TextDocumentHandle(document())))
        assertThrows(IOException::class.java) { processor.open(inputs, ProcessorOptions(mode = FlowMode.PUSH)) }
        assertThrows(IOException::class.java) { processor.open(inputs, ProcessorOptions(values = mapOf("unknown" to "true"))) }
    }
    @Test fun explicitFileTypeWinsOverContentHints() {
        val json = object : TextReparser {
            override val name = "JSON"
            override val extensions = setOf("json")
            override val mimeSuffixes = setOf("+json")
            override val leadingCharacters = setOf('{', '[')
            override fun format(text: String, cancellation: CancellationToken) = text
        }
        val toml = object : TextReparser {
            override val name = "TOML"
            override val extensions = setOf("toml")
            override fun format(text: String, cancellation: CancellationToken) = text
        }
        val registry = TextReparserRegistry(listOf(json, toml))
        assertSame(toml, registry.detect("CONFIG.TOML", "application/octet-stream", "[server]"))
        assertSame(json, registry.detect("save", "text/plain", "\uFEFF  { }"))
        assertSame(json, registry.detect("data", "application/geo+json; charset=utf-8", ""))
        assertNull(registry.detect("notes.txt", "text/plain", "ordinary text"))
    }

}
