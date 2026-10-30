package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class TextDocumentsTest {
    @Test fun boundedPreviewRetainsOriginalBytesAndIncompleteMetadata() {
        val input = InputStreamSource(ByteArrayInputStream("hello world".toByteArray()))
        val doc = TextDocuments.read(input, 5)
        assertEquals("hello", doc.text); assertArrayEquals("hello".toByteArray(), doc.bytes)
        assertTrue(doc.hasMore); assertEquals("false", doc.metadata.attributes["complete"])
        assertEquals(Verification.PROVISIONAL, doc.metadata.verification)
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
}
