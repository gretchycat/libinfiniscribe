package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class PlainTextIdentificationTest {
    @Test fun unknownExtensionAndOpaqueMimeAreVerifiedFromTextBytes() {
        val bytes = "plain text with an unfamiliar suffix\nsecond line\n".toByteArray()
        val found = probe("notes.data", "application/octet-stream", bytes)
        assertEquals("scribe.text.content", found.descriptor.id)
        assertEquals(MatchConfidence.VERIFIED, found.probe?.confidence)
        assertEquals("text/plain", found.probe?.metadata?.get("mimeType"))
        assertEquals("UTF-8", found.probe?.metadata?.get("charset"))

        // The suffix/MIME can be a wrong extension association; content still wins
        // once the named format's own signature/parser does not validate it.
        assertNotNull(probeOrNull("notes.png", "image/png", "still plain text\n".toByteArray()))
        assertNotNull(probeOrNull("README", "application/octet-stream", "text without a suffix".toByteArray()))
    }

    @Test fun cp437TextAndBomUnicodeTextAreAccepted() {
        val cp437 = "╔═╗\r\n║x║\r\n╚═╝".toByteArray(DosTextEncoding.cp437)
        assertEquals("IBM437", probe("opaque.bin", "application/octet-stream", cp437)
            .probe?.metadata?.get("charset"))
        assertNotNull(probeOrNull("unknown.raw", "application/octet-stream",
            "\u001b[M1c4\u000e".toByteArray(Charsets.US_ASCII)))

        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "hello\n".toByteArray(Charsets.UTF_16LE)
        assertEquals("UTF-16LE", probe("no-extension", "application/octet-stream", utf16)
            .probe?.metadata?.get("charset"))
    }

    @Test fun binaryFilesRemainUnsupportedAndMetadataOnlyUnknownsAreNotClaimedAsText() {
        assertTrue(ScribeCapabilities.registry.supportedMimeTypes(Operations.DECODE)
            .contains("application/octet-stream"))
        val binary = ByteArray(256) { it.toByte() }
        assertNull(probeOrNull("mystery", "application/octet-stream", binary))

        val metadataOnly = CapabilityResource(StreamMetadata(name = "unknown.payload",
            content = ContentType(mimeType = "application/octet-stream")))
        assertTrue(ScribeCapabilities.registry.probe(metadataOnly, Operations.DECODE)
            .none { it.descriptor.id == "scribe.text.content" })
    }

    private fun probe(name: String, mime: String, bytes: ByteArray) =
        probeOrNull(name, mime, bytes) ?: fail("Expected a text content match").let { error("unreachable") }

    private fun probeOrNull(name: String, mime: String, bytes: ByteArray): DiscoveredCapability? {
        val metadata = StreamMetadata(name = name, content = ContentType(mimeType = mime), length = bytes.size.toLong())
        val source = object : SourceResource {
            override val metadata = metadata
            override fun open(): ByteSource = InputStreamSource(ByteArrayInputStream(bytes), metadata, ownsInput = true)
        }
        val resource = CapabilityResource(metadata, source = source)
        return ScribeCapabilities.registry.probe(resource, Operations.DECODE)
            .firstOrNull { it.descriptor.id == "scribe.text.content" }
    }
}
