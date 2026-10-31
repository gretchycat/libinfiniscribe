package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files

class EpubDocumentsTest {
    private class Resources(val files: MutableMap<String, ByteArray>) : DocumentResources {
        override val metadata = StreamMetadata(ContentType(DataKind.DIRECTORY), name = "book.epub", length = 999,
            attributes = mapOf("storage" to "document-resources"), verification = Verification.VERIFIED,
            capabilities = setOf(Capability.POSITIONAL_READ), warnings = listOf("input warning"),
            provenance = Provenance(source = "test://book", checksums = mapOf("sha256" to "encoded-only")))
        val opened = mutableMapOf<String, Int>(); val closed = mutableMapOf<String, Int>(); val bytes = mutableMapOf<String, Int>()
        var packageCloses = 0
        var maxRead = Int.MAX_VALUE
        override fun paths() = files.keys.asSequence()
        override fun source(path: String): SourceResource? {
            val data = files[path] ?: return null
            return object : SourceResource {
                override val metadata = StreamMetadata(length = data.size.toLong())
                override fun open(): ByteSource {
                    opened[path] = (opened[path] ?: 0) + 1
                    return InputStreamSource(object : ByteArrayInputStream(data) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, maxRead)).also {
                            if (it > 0) bytes[path] = (bytes[path] ?: 0) + it
                        }
                        override fun close() { closed[path] = (closed[path] ?: 0) + 1; super.close() }
                    })
                }
            }
        }
        override fun close() { packageCloses++ }
    }
    private fun resources(opf: String = "", toc: String? = null): Resources {
        val content = opf.ifEmpty { """<package><metadata><title>Book</title><creator>Author</creator></metadata><manifest><item id="b" href="b.xhtml"/><item id="a" href="a.xhtml"/><item id="extra" href="extra.xhtml"/><item id="ncx" href="toc.ncx"/></manifest><spine><itemref idref="a"/><itemref idref="b"/><itemref idref="extra" linear="no"/></spine></package>""" }
        return Resources(linkedMapOf("META-INF/container.xml" to "<container><rootfile full-path=\"OPS/book.opf\"/></container>".toByteArray(),
            "OPS/book.opf" to content.toByteArray(), "OPS/a.xhtml" to "<title>HTML A</title>".toByteArray(),
            "OPS/b.xhtml" to "<title>HTML B</title>".toByteArray(), "OPS/extra.xhtml" to "<title>Extra</title>".toByteArray()).apply {
                if (toc != null) put("OPS/toc.ncx", toc.toByteArray())
            })
    }
    @Test fun spineOrderNonlinearExclusionAndNcxTitlesRetainPriority() {
        val r = resources(toc = "<ncx><navPoint><navLabel><text>NCX A</text></navLabel><content src=\"a.xhtml#note\"/></navPoint></ncx>")
        val book = EpubDocuments.read(r)
        assertEquals("Book", book.title); assertEquals("Author", book.author)
        assertEquals(listOf("a", "b"), book.chapters.map { it.id })
        assertEquals(listOf("NCX A", "HTML B"), book.chapters.map { it.title })
        assertFalse("OPS/a.xhtml" in r.opened) // A title already supplied by NCX needs no HTML read.
    }
    @Test fun structuredOutputDescribesActualBookAndBorrowsInput() {
        val r = resources(); val book = EpubDocuments.read(r)
        assertEquals(DataKind.STRUCTURED, book.metadata.content.kind)
        assertEquals("2", book.metadata.attributes["chapterCount"])
        assertEquals("OPS/book.opf", book.metadata.attributes["packagePath"])
        assertNull(book.metadata.length); assertTrue(book.metadata.capabilities.isEmpty())
        assertEquals(Verification.UNKNOWN, book.metadata.verification)
        assertEquals("test://book", book.metadata.provenance.source)
        assertEquals(r.metadata.provenance.checksums, book.metadata.provenance.checksums)
        assertEquals(listOf("input warning"), book.metadata.warnings)
        assertEquals(r.opened, r.closed); assertEquals(0, r.packageCloses)
        book.close(); assertEquals(0, r.packageCloses)
    }
    @Test fun absentRootfilePathSearchesForOpf() {
        val r = resources(); r.files["META-INF/container.xml"] = "<container/>".toByteArray()
        assertEquals(2, EpubDocuments.read(r).chapters.size)
    }
    @Test fun emptySpineFallsBackToManifestOrderAndOriginalNumbering() {
        val r = resources("<package><manifest><item id=\"a\" href=\"a.xhtml\"/><item id=\"missing\" href=\"missing.html\"/><item id=\"b\" href=\"b.xhtml\"/></manifest></package>")
        r.files["OPS/a.xhtml"] = "<title>Untitled</title><h1>Heading <em>fallback</em></h1>".toByteArray()
        r.files["OPS/b.xhtml"] = "<p>No title</p>".toByteArray()
        val book = EpubDocuments.read(r)
        assertEquals("EPUB Reader", book.title)
        assertEquals(listOf("Heading fallback", "Chapter 3"), book.chapters.map { it.title })
        assertEquals(listOf("item_0", "item_2"), book.chapters.map { it.id })
    }
    @Test fun unicodeNamespacesEntitiesAndSplitTextKeepMetadata() {
        val r = resources("<opf:package xmlns:opf=\"urn:opf\" xmlns:dc=\"urn:dc\"><opf:metadata><dc:title>Café &amp; <![CDATA[日本語]]></dc:title><dc:creator>First Author</dc:creator><dc:creator>Second</dc:creator></opf:metadata></opf:package>")
        val book = EpubDocuments.read(r)
        assertEquals("Café & 日本語", book.title); assertEquals("First Author", book.author)
    }
    @Test fun utf16OpfIsDetectedByXmlBackend() {
        val r = resources(); r.files["OPS/book.opf"] = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><package><metadata><title>日本語</title></metadata></package>".toByteArray(Charsets.UTF_16)
        assertEquals("日本語", EpubDocuments.read(r).title)
    }
    @Test fun ncxRelativeToItsOwnDirectoryAndEncodedPathsResolveCorrectly() {
        val r = resources("<package><manifest><item id=\"a\" href=\"text/a+b%20c.xhtml#start\"/><item id=\"toc\" href=\"toc/navigation.ncx\"/></manifest><spine><itemref idref=\"a\"/></spine></package>",
            "<ncx><navLabel><text>Resolved title</text></navLabel><content src=\"../text/a+b%20c.xhtml#note\"/></ncx>")
        r.files["OPS/toc/navigation.ncx"] = r.files.remove("OPS/toc.ncx")!!
        r.files["OPS/text/a+b c.xhtml"] = "<title>Fallback</title>".toByteArray()
        val chapter = EpubDocuments.read(r).chapters.single()
        assertEquals("OPS/text/a+b c.xhtml", chapter.path); assertEquals("Resolved title", chapter.title)
    }
    @Test fun malformedNcxFallsBackAndRetainsWarning() {
        val r = resources(toc = "<ncx><broken>")
        val book = EpubDocuments.read(r)
        assertEquals("HTML A", book.chapters.first().title)
        assertTrue(book.metadata.warnings.any { it.contains("NCX") }); assertEquals(r.opened, r.closed)
    }
    @Test fun missingContainerAndPackageFailExplicitly() {
        val r = resources(); r.files.remove("META-INF/container.xml")
        assertThrows(IOException::class.java) { EpubDocuments.read(r) }
        val other = resources(); other.files.remove("OPS/book.opf")
        assertThrows(IOException::class.java) { EpubDocuments.read(other) }
        assertEquals(other.opened, other.closed)
    }
    @Test fun unsafePackageAndChapterPathsNeverOpenOutsideResources() {
        val r = resources(); r.files["META-INF/container.xml"] = "<container><rootfile full-path=\"../outside.opf\"/></container>".toByteArray()
        assertThrows(IOException::class.java) { EpubDocuments.read(r) }
        val other = resources("<package><manifest><item id=\"x\" href=\"../../outside.xhtml\"/></manifest><spine><itemref idref=\"x\"/></spine></package>")
        assertTrue(EpubDocuments.read(other).chapters.isEmpty())
        assertFalse(other.opened.keys.any { it.contains("outside") })
    }
    @Test fun htmlTitleSniffingIsBoundedAndDoesNotReadOtherResources() {
        val r = resources(); r.files["OPS/a.xhtml"] = ("<p>" + "x".repeat(100_000) + "<title>Too late</title>").toByteArray()
        r.files["OPS/image.bin"] = ByteArray(100_000)
        assertEquals("Chapter 1", EpubDocuments.read(r).chapters.first().title)
        assertTrue(r.bytes.getValue("OPS/a.xhtml") <= 8192); assertFalse("OPS/image.bin" in r.opened)
    }
    @Test fun legalSingleByteReadsPreserveXmlAndHtmlTitles() {
        val r = resources(); r.maxRead = 1
        assertEquals(listOf("HTML A", "HTML B"), EpubDocuments.read(r).chapters.map { it.title })
        assertEquals(r.opened, r.closed)
    }
    @Test fun defaultBudgetsRetainLargeValidMetadataCompatibility() {
        val r = resources()
        r.files["OPS/book.opf"] = ("<package><!--" + "x".repeat(2 * 1024 * 1024) + "--><metadata><title>Large metadata</title></metadata></package>").toByteArray()
        assertEquals("Large metadata", EpubDocuments.read(r).title)
        assertEquals(r.opened, r.closed)
    }
    @Test fun externalDtdNeverNeedsNetworkAccess() {
        val r = resources(toc = "<!DOCTYPE ncx SYSTEM \"file:///definitely-not-readable/evil.dtd\"><ncx><navLabel><text>Local NCX</text></navLabel><content src=\"a.xhtml\"/></ncx>")
        assertEquals("Local NCX", EpubDocuments.read(r).chapters.first().title)
    }
    @Test fun internalEntitiesAndExcessiveXmlNestingAreRejected() {
        val r = resources(); r.files["OPS/book.opf"] = "<!DOCTYPE package [<!ENTITY x 'expanded'>]><package><metadata><title>&x;</title></metadata></package>".toByteArray()
        assertThrows(Exception::class.java) { EpubDocuments.read(r) }
        r.files["OPS/book.opf"] = "<!DOCTYPE package SYSTEM 'file:///missing.dtd' [<!ENTITY x 'expanded'>]><package><metadata><title>&x;</title></metadata></package>".toByteArray()
        assertThrows(Exception::class.java) { EpubDocuments.read(r) }
        r.files["OPS/book.opf"] = ("<package>" + "<x>".repeat(130) + "</x>".repeat(130) + "</package>").toByteArray()
        assertThrows(Exception::class.java) { EpubDocuments.read(r, limits = EpubReadLimits(maxXmlDepth = 128)) }
        assertEquals(r.opened, r.closed)
    }
    @Test fun xmlByteBudgetFailsInsteadOfReadingUnboundedInput() {
        val r = resources(); r.files["OPS/book.opf"] = ("<package><!--" + "x".repeat(2 * 1024 * 1024) + "--></package>").toByteArray()
        assertThrows(Exception::class.java) { EpubDocuments.read(r, limits = EpubReadLimits(maxXmlBytes = 1024 * 1024)) }
        assertTrue(r.bytes.getValue("OPS/book.opf") <= 1024 * 1024 + 1)
        assertEquals(r.opened, r.closed)
    }
    @Test fun cancellationBeforeAndDuringParsingPropagatesAndClosesOwnedStreams() {
        val r = resources()
        assertThrows(InterruptedIOException::class.java) { EpubDocuments.read(r, cancellation = CancellationToken { true }) }
        assertTrue(r.opened.isEmpty())
        var calls = 0
        assertThrows(InterruptedIOException::class.java) { EpubDocuments.read(r, cancellation = CancellationToken { ++calls > 12 }) }
        assertEquals(r.opened, r.closed)
    }
    @Test fun processorRejectsUnsupportedModeAndWrongInput() {
        val processor = EpubParseProcessor()
        assertThrows(IOException::class.java) { processor.open(emptyMap(), ProcessorOptions(mode = FlowMode.PUSH)) }
        assertThrows(IOException::class.java) { processor.open(emptyMap(), ProcessorOptions()) }
    }
    @Test fun packageReferenceResolutionPreservesPlusFragmentsAndSafeParents() {
        assertEquals("OPS/text/a+b c.xhtml", DocumentPaths.resolve("OPS/book.opf", "text/a+b%20c.xhtml#note"))
        assertEquals("OPS/text/a.xhtml", DocumentPaths.resolve("OPS/text/a.xhtml", "#note"))
        assertEquals("OPS/images/x.svg", DocumentPaths.resolve("OPS/text/a.xhtml", "../images/x.svg"))
        for (path in listOf("../../outside", "%2Foutside", "https://example.org/file", "a\\b", "%00", "%GG"))
            assertNull(path, DocumentPaths.resolve("OPS/book.opf", path))
    }
    @Test fun directoryResourcesArePositionalBorrowedAndConfined() {
        val root = Files.createTempDirectory("scribe-related").toFile()
        try {
            File(root, "hello+world.txt").writeText("hello")
            val resources = DirectoryDocumentResources(root, StreamMetadata(provenance = Provenance(source = "test://original")))
            assertEquals(listOf("hello+world.txt"), resources.paths().toList())
            assertNull(resources.source("../outside")); assertNull(resources.source("/outside"))
            val value = resources.source("hello+world.txt")!!
            value.open().use { source ->
                assertTrue(source is PositionalReadable); assertEquals("test://original", source.metadata.provenance.source)
                val buffer = ByteArray(5); (source as PositionalReadable).readAt(0, buffer)
                assertEquals("hello", buffer.decodeToString())
            }
            resources.close(); assertTrue(File(root, "hello+world.txt").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun directoryResourcesRejectEscapingSymlinksAndDoNotEnumerateCycles() {
        val base = Files.createTempDirectory("scribe-confined").toFile()
        try {
            val root = File(base, "root").apply { mkdirs() }
            val outside = File(base, "outside.txt").apply { writeText("outside") }
            File(root, "inside.txt").writeText("inside")
            Files.createSymbolicLink(File(root, "escape.txt").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
            val resources = DirectoryDocumentResources(root)
            assertNull(resources.source("escape.txt"))
            assertEquals(listOf("inside.txt"), resources.paths().toList())
        } finally { base.deleteRecursively() }
    }
}
