package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class TextDocumentsTest {
    @Test fun boundedPreviewRetainsOriginalBytesAndIncompleteMetadata() {
        val input = InputStreamSource(ByteArrayInputStream("hello world".toByteArray()), ownsInput = false)
        val doc = TextDocuments.read(input, 5)
        assertEquals("hello", doc.text); assertArrayEquals("hello".toByteArray(), doc.bytes)
        assertTrue(doc.hasMore); assertEquals("false", doc.metadata.attributes["complete"])
        assertEquals(Verification.PROVISIONAL, doc.metadata.verification)
        assertEquals(Ownership.OWNED, doc.metadata.ownership)
    }
    @Test fun completeEmptyAndAlternateEncodingRemainFaithful() {
        val bytes = "abc\u00e9".toByteArray(Charsets.ISO_8859_1)
        val doc = TextDocuments.read(InputStreamSource(ByteArrayInputStream(bytes)), bytes.size, Charsets.ISO_8859_1)
        assertEquals("abc\u00e9", doc.text); assertFalse(doc.hasMore); assertArrayEquals(bytes, doc.bytes)
        assertFalse(TextDocuments.read(InputStreamSource(ByteArrayInputStream(byteArrayOf())), 0).hasMore)
    }
    @Test fun cancellationAndSourceFailuresAreNotTreatedAsEmptyText() {
        assertThrows(InterruptedIOException::class.java) {
            TextDocuments.read(InputStreamSource(ByteArrayInputStream(byteArrayOf(1))), cancellation = CancellationToken { true })
        }
        val source = object : InputStream() { override fun read(): Int = throw IOException("broken") }
        assertThrows(IOException::class.java) { TextDocuments.read(InputStreamSource(source)) }
    }
    @Test fun encodedTextMetadataStillExecutesTheDecoderAndBorrowsItsInput() {
        var closes = 0
        val raw = object : ByteArrayInputStream("encoded text".toByteArray()) { override fun close() { closes++; super.close() } }
        val input = InputStreamSource(raw, StreamMetadata(ContentType(DataKind.TEXT, "text/plain", "text", "UTF-8"),
            attributes = mapOf("charset" to "UTF-8")))
        val doc = TextDocuments.read(input)
        assertEquals("encoded text", doc.text); assertEquals("text-document", doc.metadata.attributes["storage"])
        assertEquals(0, closes); input.close(); assertEquals(1, closes)
    }

    @Test fun sourceVerificationIsProvenanceRatherThanProofOfDecodedText() {
        val input = InputStreamSource("verified bytes".byteInputStream(), StreamMetadata(
            provenance = Provenance(source = "fixture://original", checksums = mapOf("source-sha256" to "example")),
            verification = Verification.VERIFIED))
        input.use {
            val doc = TextDocuments.read(input)
            assertEquals("fixture://original", doc.metadata.provenance.source)
            assertEquals(Verification.UNKNOWN, doc.metadata.verification)
            assertEquals("replace-invalid", doc.metadata.attributes["decoding"])
            assertEquals("unicode", doc.metadata.content.encoding)
            assertEquals("UTF-8", doc.metadata.attributes["sourceCharset"])
            assertEquals(Finiteness.FINITE, doc.metadata.finiteness)
        }
    }

}
