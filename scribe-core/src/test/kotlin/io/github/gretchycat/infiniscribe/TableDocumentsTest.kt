package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class TableDocumentsTest {
    private fun text(value: String, maxBytes: Int = 1024): TextDocument = TextDocuments.read(
        InputStreamSource(value.byteInputStream(), StreamMetadata(provenance = Provenance(
            source = "fixture://table", checksums = mapOf("source-sha256" to "example")),
            warnings = listOf("source warning"), verification = Verification.VERIFIED)), maxBytes)

    @Test fun legacyWhitespaceBlankRowsQuotesAndLineEndingsStayTheSame() {
        assertEquals(listOf(listOf("a", "b\r\nc"), listOf("x", "y")),
            DelimitedText.parseCsv("  a  ,\" b\r\nc \"\r\n\r\n , \nx,y\r\n"))
        assertEquals(listOf(listOf("open,quote\nnext")), DelimitedText.parseCsv("\"open,quote\nnext"))
        assertEquals(listOf(listOf("a", "", "")), DelimitedText.parseCsv("a,,"))
        assertEquals(listOf(listOf("", "")), DelimitedText.parseCsv(","))
        assertEquals(emptyList<List<String>>(), DelimitedText.parseCsv(",\n"))
        assertEquals(emptyList<List<String>>(), DelimitedText.parseCsv("\"\""))
    }

    @Test fun delimiterHeuristicsKeepExistingTiesAndFilenamePreference() {
        assertEquals('\t', DelimitedText.detectDelimiter("a,b,c", "UPPER.TSV"))
        assertEquals(',', DelimitedText.detectDelimiter("a,b;c"))
        // Existing heuristic compares tabs to commas/semicolons before examining pipes.
        assertEquals('\t', DelimitedText.detectDelimiter("a\tb|c|d|e"))
        assertEquals(',', DelimitedText.detectDelimiter("\nnext;line;ignored"))
        assertEquals(',', DelimitedText.detectDelimiter(""))
    }

    @Test fun parsedOutputDescribesActualCellsAndPreservesOriginalProvenance() {
        val original = text("name,city\r\n\" A \",Paris\r\nBob")
        val table = TableDocuments.parse(original)
        assertEquals(listOf(listOf("name", "city"), listOf("A", "Paris"), listOf("Bob")), table.rows)
        assertEquals(DataKind.STRUCTURED, table.metadata.content.kind)
        assertEquals("csv", table.metadata.content.format)
        assertEquals("3", table.metadata.attributes["rowCount"])
        assertEquals("2", table.metadata.attributes["columnCount"])
        assertEquals("true", table.metadata.attributes["complete"])
        assertEquals("table-document", table.metadata.attributes["storage"])
        assertEquals("UTF-8", table.metadata.attributes["sourceCharset"])
        assertNull(table.metadata.length); assertTrue(table.metadata.capabilities.isEmpty())
        assertEquals(Finiteness.FINITE, table.metadata.finiteness)
        assertEquals(Ownership.OWNED, table.metadata.ownership)
        assertEquals(Verification.UNKNOWN, table.metadata.verification)
        assertEquals(original.metadata.provenance.transformations + "scribe.table.parse", table.metadata.provenance.transformations)
        assertEquals("fixture://table", table.metadata.provenance.source)
        assertEquals("example", table.metadata.provenance.checksums["source-sha256"])
        assertEquals(listOf("source warning"), table.metadata.warnings)
        assertArrayEquals("name,city\r\n\" A \",Paris\r\nBob".toByteArray(), original.bytes)
    }

    @Test fun truncatedPreviewRemainsIncompleteThroughParsingAndFormatting() {
        val table = TableDocuments.parse(text("a,b\nc,d\ne,f", 7))
        assertEquals("false", table.metadata.attributes["complete"])
        assertEquals(Verification.PROVISIONAL, table.metadata.verification)
        assertTrue(table.metadata.warnings.contains("Text preview is truncated"))
        val formatted = TableDocuments.format(table)
        assertTrue(formatted.hasMore)
        assertEquals("false", formatted.metadata.attributes["complete"])
        assertEquals(Verification.PROVISIONAL, formatted.metadata.verification)
        assertTrue(formatted.metadata.warnings.contains("Text preview is truncated"))
    }

    @Test fun serializationUsesCurrentCellsUtf8AndTheSameQuotingAndLfRules() {
        val table = TableDocuments.parse(text("a\tb\n1\t2"), "table.tsv")
        val edited = table.copy(rows = listOf(listOf("é", "line\nbreak", "\"quote\"", "a\tb")))
        val output = TableDocuments.format(edited)
        assertEquals("é\t\"line\nbreak\"\t\"\"\"quote\"\"\"\t\"a\tb\"\n", output.text)
        assertArrayEquals(output.text.toByteArray(Charsets.UTF_8), output.bytes)
        assertFalse(output.hasMore)
        assertEquals("1", output.metadata.attributes["rowCount"])
        assertEquals("4", output.metadata.attributes["columnCount"])
        assertEquals(output.text.length.toString(), output.metadata.attributes["utf16CodeUnits"])
        assertEquals("text/tab-separated-values", output.metadata.content.mimeType)
        assertEquals(DataKind.TEXT, output.metadata.content.kind)
        assertEquals("unicode", output.metadata.content.encoding)
        assertEquals("LF", output.metadata.attributes["lineEnding"])
        assertEquals("text-document", output.metadata.attributes["storage"])
        assertEquals("fixture://table", output.metadata.provenance.source)
        assertEquals("scribe.table.format", output.metadata.provenance.transformations.last())
        assertEquals(Verification.UNKNOWN, output.metadata.verification)
        assertTrue(output.metadata.capabilities.isEmpty()); assertNull(output.metadata.length)
    }

    @Test fun explicitDelimiterAndEmptyTablesHaveHonestOutputMetadata() {
        val table = TableDocuments.parse(text(""), "table.tsv", ';')
        assertEquals(';', table.delimiter)
        assertEquals("delimited-text", table.metadata.content.format)
        assertEquals("0", table.metadata.attributes["rowCount"])
        assertEquals("0", table.metadata.attributes["columnCount"])
        assertEquals("", TableDocuments.format(table).text)
    }

    @Test fun processorsRejectWrongRepresentationsAndUnsupportedOptions() {
        val encoded = InputStreamSource("a,b".byteInputStream())
        assertThrows(IOException::class.java) { TableParseProcessor().open(mapOf("text" to listOf(encoded))) }
        val value = TextDocumentHandle(text("a,b"))
        assertThrows(IOException::class.java) { TableParseProcessor().open(mapOf("text" to listOf(value)), ProcessorOptions(mode = FlowMode.PUSH)) }
        assertThrows(IOException::class.java) { TableFormatProcessor().open(mapOf("table" to listOf(value))) }
        assertThrows(IOException::class.java) { TableFormatProcessor().open(emptyMap(), ProcessorOptions(mapOf("unexpected" to "true"))) }
    }

    @Test fun cancellationStopsBothProcessorsAndLongSingleRowParsing() {
        val cancelled = CancellationToken { true }
        assertThrows(InterruptedIOException::class.java) { TableDocuments.parse(text("a,b"), cancellation = cancelled) }
        val table = TableDocuments.parse(text("a,b"))
        assertThrows(InterruptedIOException::class.java) { TableDocuments.format(table, cancelled) }
        var checks = 0
        assertThrows(InterruptedIOException::class.java) {
            DelimitedText.parseCsv("\"" + "\"\"".repeat(20000), cancellation = CancellationToken { ++checks > 2 })
        }
        assertEquals(3, checks)
    }
}
