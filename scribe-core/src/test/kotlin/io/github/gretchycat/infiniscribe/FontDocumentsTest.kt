package io.github.gretchycat.infiniscribe

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises the real table reader with encoded SFNT data, not a mocked decoder. */
class FontDocumentsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
    private fun inspect(bytes: ByteArray): FontDocument {
        val file = temp.newFile(); file.writeBytes(bytes)
        val result = FontDocuments.inspect(file)
        assertArrayEquals("Inspection must not modify the source", bytes, file.readBytes())
        return result
    }
    private fun nameTable(family: String, preferred: String? = null): ByteArray {
        val names = linkedMapOf(1 to family, 2 to "Regular", 4 to "$family Regular", 6 to "Specimen-Regular")
        if (preferred != null) { names[16] = preferred; names[17] = "Book" }
        val strings = names.values.map { it.toByteArray(Charsets.UTF_16BE) }
        val start = 6 + names.size * 12
        val out = buffer(start + strings.sumOf { it.size })
        out.putShort(0); out.putShort(names.size.toShort()); out.putShort(start.toShort())
        var offset = 0
        names.keys.forEachIndexed { i, id ->
            out.putShort(3); out.putShort(1); out.putShort(0x0409); out.putShort(id.toShort())
            out.putShort(strings[i].size.toShort()); out.putShort(offset.toShort()); offset += strings[i].size
        }
        strings.forEach { out.put(it) }
        return out.array()
    }
    private fun font(family: String = "Specimen", signature: Int = 0x00010000,
        preferred: String? = null, variable: Boolean = false, named: Boolean = true): ByteArray {
        val tables = linkedMapOf<String, ByteArray>()
        if (named) tables["name"] = nameTable(family, preferred)
        tables["head"] = buffer(54).apply { putShort(18, 2048) }.array()
        tables["maxp"] = buffer(6).apply { putInt(0x00010000); putShort(320) }.array()
        tables["OS/2"] = buffer(6).apply { putShort(4, 400) }.array()
        if (variable) tables["fvar"] = buffer(36).apply {
            putInt(0x00010000); putShort(16); putShort(2); putShort(1); putShort(20); putShort(0); putShort(4)
            put("wght".toByteArray(Charsets.US_ASCII)); putInt(100 * 65536); putInt(400 * 65536); putInt(900 * 65536)
            putShort(0); putShort(256)
        }.array()
        fun aligned(n: Int) = (n + 3) and -4
        var next = 12 + tables.size * 16
        val out = buffer(next + tables.values.sumOf { aligned(it.size) })
        out.putInt(signature); out.putShort(tables.size.toShort()); out.putShort(0); out.putShort(0); out.putShort(0)
        tables.forEach { (tag, bytes) ->
            out.put(tag.toByteArray(Charsets.US_ASCII)); out.putInt(0); out.putInt(next); out.putInt(bytes.size)
            System.arraycopy(bytes, 0, out.array(), next, bytes.size); next += aligned(bytes.size)
        }
        return out.array()
    }
    private fun tableOffset(bytes: ByteArray, index: Int = 0) = ByteBuffer.wrap(bytes).getInt(12 + index * 16 + 8)

    @Test fun trueTypeNamesAndMetricsAreDecodedFromTheFile() {
        val bytes = font("Málaga 日本語")
        val doc = inspect(bytes); val face = doc.faces.single()
        assertEquals("TrueType", doc.format); assertEquals(bytes.size.toLong(), doc.length)
        assertEquals("Málaga 日本語", face.family); assertEquals("Málaga 日本語 Regular", face.fullName)
        assertEquals("Regular", face.style); assertEquals("Specimen-Regular", face.postScriptName)
        assertEquals(320, face.glyphCount); assertEquals(2048, face.unitsPerEm); assertEquals(400, face.weight)
        assertTrue(face.axes.isEmpty()); assertEquals(0, face.index)
    }
    @Test fun openTypeAndTypographicNamesRetainTheirIdentity() {
        val doc = inspect(font("Legacy", 0x4f54544f, "Preferred Family"))
        assertEquals("OpenType (CFF)", doc.format)
        assertEquals("Preferred Family", doc.faces.single().family); assertEquals("Book", doc.faces.single().style)
    }
    @Test fun unnamedFontsKeepMetricsWithoutInventingNames() {
        val face = inspect(font(named = false)).faces.single()
        assertNull(face.family); assertNull(face.style); assertNull(face.fullName); assertNull(face.postScriptName)
        assertEquals(320, face.glyphCount)
    }
    @Test fun collectionFacesUseAbsoluteTableOffsetsAndKeepSeparateNames() {
        val fonts = listOf(font("First"), font("Second", 0x4f54544f))
        val headerSize = 12 + fonts.size * 4 + 12 // TTC v2 includes three zero DSIG fields.
        val out = buffer(headerSize + fonts.sumOf { it.size })
        out.putInt(0x74746366); out.putInt(0x00020000); out.putInt(fonts.size)
        var start = headerSize
        fonts.forEach { original ->
            out.putInt(start)
            val bytes = original.copyOf(); val view = ByteBuffer.wrap(bytes)
            repeat(view.getShort(4).toInt() and 65535) { i ->
                val position = 12 + i * 16 + 8; view.putInt(position, view.getInt(position) + start)
            }
            System.arraycopy(bytes, 0, out.array(), start, bytes.size); start += bytes.size
        }
        val doc = inspect(out.array())
        assertEquals("OpenType / TrueType collection", doc.format)
        assertEquals(listOf("First", "Second"), doc.faces.map { it.family })
        assertEquals(listOf(0, 1), doc.faces.map { it.index })
    }
    @Test fun variableFontAxesComeFromTheirEncodedRanges() {
        val axis = inspect(font(variable = true)).faces.single().axes.single()
        assertEquals("wght", axis.tag); assertEquals(100f, axis.minimum, 0f)
        assertEquals(400f, axis.default, 0f); assertEquals(900f, axis.maximum, 0f)
    }
    @Test fun tableRangesAndUnsignedOffsetsAreChecked() {
        for (offset in listOf(-1, Int.MAX_VALUE)) {
            val bytes = font(); ByteBuffer.wrap(bytes).putInt(20, offset)
            assertThrows(IOException::class.java) { inspect(bytes) }
        }
        val bytes = font(); ByteBuffer.wrap(bytes).putInt(24, bytes.size)
        assertThrows(IOException::class.java) { inspect(bytes) }
    }
    @Test fun truncatedDirectoriesAndDuplicateTablesAreRejected() {
        assertThrows(IOException::class.java) { inspect(font().copyOf(20)) }
        val duplicate = font(); System.arraycopy(duplicate, 12, duplicate, 28, 4)
        assertThrows(IOException::class.java) { inspect(duplicate) }
        val zero = font(); ByteBuffer.wrap(zero).putShort(4, 0)
        assertThrows(IOException::class.java) { inspect(zero) }
    }
    @Test fun nameStringsCannotEscapeTheirTable() {
        val bytes = font(); val name = tableOffset(bytes)
        ByteBuffer.wrap(bytes).putShort(name + 6 + 10, 0x7fff)
        assertThrows(IOException::class.java) { inspect(bytes) }
    }
    @Test fun invalidCollectionsAndExcessiveFaceCountsAreRejected() {
        for (count in listOf(0, 257, -1)) {
            val bytes = buffer(16).putInt(0x74746366).putInt(0x00010000).putInt(count).putInt(12).array()
            assertThrows(IOException::class.java) { inspect(bytes) }
        }
    }
    @Test fun invalidVariationRangesAreRejected() {
        val bytes = font(variable = true); val fvar = tableOffset(bytes, 4)
        ByteBuffer.wrap(bytes).putInt(fvar + 20, 1000 * 65536)
        assertThrows(IOException::class.java) { inspect(bytes) }
    }
    @Test fun fileSizeLimitIsCheckedWithoutReadingAnOversizedBody() {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(FontDocuments.MAX_BYTES + 1) }
        assertThrows(IOException::class.java) { FontDocuments.inspect(file) }
        assertEquals(FontDocuments.MAX_BYTES + 1, file.length())
    }
    @Test fun unsupportedContainersAndTruncatedHeadersAreRejected() {
        for (bytes in listOf(byteArrayOf(0, 1), "wOFF".toByteArray() + ByteArray(32), "wOF2".toByteArray() + ByteArray(32)))
            assertThrows(IOException::class.java) { inspect(bytes) }
    }
    @Test fun interruptionCancelsInspectionAndDoesNotModifyTheFile() {
        val file = temp.newFile(); val bytes = font(); file.writeBytes(bytes)
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) { FontDocuments.inspect(file) }
        } finally { Thread.interrupted() }
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun supportedRoutesDoNotClaimEveryFontContainer() {
        for (ext in listOf("ttf", "OTF", "ttc", "otc")) assertTrue(FontFormats.supports("font.$ext", ""))
        assertTrue(FontFormats.supports("opaque", "font/ttf; charset=binary"))
        assertTrue(FontFormats.supports("opaque", "application/font-sfnt"))
        for (ext in listOf("woff", "woff2", "fon", "fnt", "psf", "pfa", "pfb"))
            assertFalse(FontFormats.supports("font.$ext", "font/$ext"))
    }
}
