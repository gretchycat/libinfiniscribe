package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class DocumentChunksTest {
    private class Positional(private val bytes: ByteArray, size: Long = bytes.size.toLong(), val shortRead: Int = Int.MAX_VALUE) : ByteSource, PositionalReadable {
        override val metadata = StreamMetadata(length = size, provenance = Provenance(source = "fixture://original"), warnings = listOf("source warning"))
        var fetched = 0L; var closed = false; val positions = mutableListOf<Long>()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw AssertionError("Sequential cursor must not move")
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            positions += position
            val n = minOf(length.toLong(), metadata.length!! - position, shortRead.toLong()).toInt()
            if (n <= 0) return -1
            repeat(n) { buffer[offset + it] = bytes[((position + it) % bytes.size).toInt()] }
            fetched += n; return n
        }
        override fun close() { closed = true }
    }
    @Test fun largeSourceReadsOnlyDiscreteRangesAndCanJumpBeyondFourGiB() {
        val source = Positional("hello world\n".toByteArray(), 12L * 1024 * 1024 * 1024)
        TextChunkReader(source).use { reader ->
            val first = reader.read(); assertTrue(first.hasNext); assertTrue(first.document.hasMore)
            val far = reader.read(8L * 1024 * 1024 * 1024)
            assertEquals(8L * 1024 * 1024 * 1024, far.start)
            assertTrue(source.fetched <= 4 * 16 * 1024)
            assertTrue(source.positions.any { it >= far.start })
            assertTrue(reader.supportsRandomAccess)
            assertEquals("fixture://original", far.document.metadata.provenance.source)
            assertEquals("false", far.document.metadata.attributes["complete"])
            assertEquals(Verification.PROVISIONAL, far.document.metadata.verification)
        }
        assertTrue(source.closed)
    }
    @Test fun utf8AndUtf16CharactersSurviveChunkBoundariesAndArbitraryJumps() {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val text = "a🎵日本語é".repeat(100)
            val source = Positional(text.toByteArray(charset), shortRead = 3)
            TextChunkReader(source, charset).use { reader ->
                var position = 0L; val decoded = StringBuilder()
                do { val chunk = reader.read(position, 17); decoded.append(chunk.document.text); position = chunk.nextOffset } while (chunk.hasNext)
                assertEquals(charset.name(), text, decoded.toString())
                assertFalse(reader.read(3, 17).document.text.contains('\ufffd'))
            }
        }
    }
    @Test fun onlyAWholeSourceCanAcquireCompleteDocumentMetadata() {
        val source = Positional("abc".toByteArray())
        TextChunkReader(source, ownsSource = false).use { reader ->
            val first = reader.read(); assertFalse(first.document.hasMore); assertFalse(first.hasNext)
            assertArrayEquals("abc".toByteArray(), first.document.bytes)
            val last = reader.read(2); assertTrue(last.document.hasMore); assertFalse(last.hasNext)
            assertEquals("false", last.document.metadata.attributes["complete"])
        }
        assertFalse(source.closed)
    }
    @Test fun csvWindowsKeepQuotedNewlinesEscapesCrLfAndOriginalByteIdentity() {
        val text = "id,notes\r\n1,\"first\nsecond\"\r\n2,\"quote \"\"inside\"\"\"\r\n3,last"
        val source = Positional(text.toByteArray(), shortRead = 2)
        TableChunkReader(source, "table.csv").use { reader ->
            val first = reader.read(maxRows = 2)
            assertEquals(listOf(listOf("id", "notes"), listOf("1", "first\nsecond")), first.table.rows)
            assertTrue(first.hasNext); assertNull(first.originalBytes)
            val second = reader.read(first.nextOffset, first.nextRowIndex, maxRows = 2)
            assertEquals(listOf(listOf("2", "quote \"inside\""), listOf("3", "last")), second.table.rows)
            assertEquals(listOf(2L, 3L), second.rowIndices)
            assertFalse(second.hasNext); assertEquals("false", second.table.metadata.attributes["complete"])
            assertEquals(first.table.rows, reader.read(maxRows = 2).table.rows)
            val whole = reader.read(maxRows = 50)
            assertArrayEquals(text.toByteArray(), whole.originalBytes)
            assertEquals("true", whole.table.metadata.attributes["complete"])
        }
    }
    @Test fun csvWindowsHandleEmptyBlankRaggedAndUnclosedQuoteLikeTheLegacyParser() {
        for (text in listOf("", "a,b\n\n , \n1,2\n3", "id,notes\n1,\"unfinished\nrest")) {
            TableChunkReader(Positional(text.toByteArray()), "table.csv").use { reader ->
                assertEquals(DelimitedText.parseCsv(text), reader.read().table.rows)
            }
        }
    }
    @Test fun csvDelimiterAndSparseFiltersAreBoundedWithoutDeclaringTheResultComplete() {
        val text = "id\tnotes\n" + (1..10000).joinToString("") { "$it\tmiss\n" }
        val source = Positional(text.toByteArray())
        TableChunkReader(source, "table.tsv").use { reader ->
            val first = reader.read(filter = "absent", maxScanBytes = 1024)
            assertEquals('\t', first.table.delimiter); assertEquals(listOf(listOf("id", "notes")), first.table.rows)
            assertTrue(first.hasNext); assertTrue(first.nextOffset < 2048)
            assertTrue(source.fetched <= 16 * 1024)
            assertEquals("false", first.table.metadata.attributes["complete"])
        }
    }
    @Test fun sequentialInputsUseABoundedCacheAndNeverAdvertiseRandomAccess() {
        val source = InputStreamSource("a".repeat(300000).byteInputStream())
        TextChunkReader(source).use { reader ->
            assertFalse(reader.supportsRandomAccess)
            reader.read(); assertTrue(reader.canRead(0)); reader.read(0)
            for (page in 1..16) reader.read(page * 16384L)
            assertFalse(reader.canRead(0))
            assertThrows(IOException::class.java) { reader.read(0) }
        }
    }
    @Test fun cancellationAndInvalidReadCountsPropagateAndClosedHandlesStayClosed() {
        val source = Positional("abc".toByteArray())
        val reader = TextChunkReader(source, cancellation = CancellationToken { true })
        assertThrows(InterruptedIOException::class.java) { reader.read() }; reader.close()
        val closed = TextChunkReader(Positional("abc".toByteArray())); closed.close()
        assertThrows(IOException::class.java) { closed.read() }
        val broken = object : ByteSource { override val metadata = StreamMetadata(); override fun close() {}
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0 }
        assertThrows(IOException::class.java) { TextChunkReader(broken).use { it.read() } }
    }

    @Test fun newlineDenseTextHasABoundedLineCountAsWellAsABoundedByteWindow() {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            TextChunkReader(Positional("\n".repeat(10000).toByteArray(charset)), charset).use { reader ->
                val chunk = reader.read()
                assertEquals(256, chunk.document.text.count { it == '\n' })
                assertTrue(chunk.hasNext)
                assertEquals(chunk.nextOffset, reader.read(chunk.nextOffset).start)
            }
        }
    }
    @Test fun csvRecordCrossingAReadBlockBoundaryMatchesTheOriginalParser() {
        val text = "id,notes\r\n1,\"" + "x".repeat(16373) + "\"\"quoted\nline\"\r\n2,last\r\n"
        TableChunkReader(Positional(text.toByteArray()), "data.csv").use { reader ->
            val first = reader.read(maxRows = 2)
            val next = reader.read(first.nextOffset, first.nextRowIndex)
            assertEquals(DelimitedText.parseCsv(text), first.table.rows + next.table.rows)
        }
    }

    @Test fun textSearchScansDiscreteWindowsAndFindsCrossBoundaryMatchesWithoutRepeatingOldOnes() {
        val value = "x".repeat(16380) + "needle" + "z".repeat(40000) + "target"
        TextChunkReader(Positional(value.toByteArray())).use { reader ->
            val first = reader.read()
            val found = reader.findNext("needle", first.nextOffset, first)!!
            assertTrue(found.document.text.contains("needle"))
            val target = reader.findNext("target", first.nextOffset, first)!!
            assertTrue(target.document.text.contains("target"))
            assertNull(reader.findNext("absent", first.nextOffset, first))
        }
        TextChunkReader(Positional(("x".repeat(16378) + "needle" + "z".repeat(100)).toByteArray())).use { reader ->
            val first = reader.read()
            assertTrue(first.document.text.endsWith("needle"))
            assertNull(reader.findNext("needle", first.nextOffset, first))
        }
    }
    @Test fun sparseRowFilteringFindsLaterRecordsWithoutReturningAnEmptyPage() {
        val value = "id,notes\n" + (1..10000).joinToString("") { "$it,miss\n" } + "10001,target\n"
        TableChunkReader(Positional(value.toByteArray()), "data.csv").use { reader ->
            val found = reader.findRows(filter = "target")
            assertEquals(listOf(listOf("10001", "target")), found.table.rows)
            assertEquals(listOf("id", "notes"), reader.firstRow)
            assertFalse(found.hasNext)
            assertEquals("false", found.table.metadata.attributes["complete"])
        }
    }

    @Test fun precedingTextWindowsMeetExactlyAndRespectUnicodeAndLineBudgets() {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val value = (1..2000).joinToString("\n") { "line $it 🎵 é" }
            TextChunkReader(Positional(value.toByteArray(charset)), charset).use { reader ->
                val first = reader.read()
                val next = reader.read(first.nextOffset)
                val previous = reader.readPrevious(next.start)
                assertEquals(next.start, previous.nextOffset)
                assertFalse(previous.document.text.contains('\ufffd'))
                assertTrue(previous.document.text.count { it == '\n' } <= 256)
                val bytes = value.toByteArray(charset).copyOfRange(previous.start.toInt(), previous.nextOffset.toInt())
                assertEquals(String(bytes, charset), previous.document.text)
                val tiny = reader.readPrevious(if (charset == Charsets.UTF_8) 5 else 2)
                assertEquals(if (charset == Charsets.UTF_8) 5L else 2L, tiny.nextOffset)
            }
        }
    }
    @Test fun reverseCsvWindowsRetainEscapedQuotesNewlinesBlankRecordsAndLogicalIndices() {
        val value = "id,notes\r\n" + (1..140).joinToString("") { "$it,\"line\nwith \"\"quotes\"\"\"\r\n\r\n" }
        TableChunkReader(Positional(value.toByteArray()), "data.csv").use { reader ->
            val first = reader.read(); val second = reader.read(first.nextOffset, first.nextRowIndex)
            val previous = reader.readPrevious(second.start, second.firstRowIndex)
            assertEquals(second.start, previous.nextOffset)
            assertEquals(first.table.rows, previous.table.rows)
            assertEquals(first.rowIndices, previous.rowIndices)
            assertEquals(0L, previous.firstRowIndex)
            assertNull(previous.originalBytes)
        }
    }
    @Test fun reverseCsvHandlesCrOnlyAndSparseFiltersAcrossBlocksWithoutAGlobalIndex() {
        val value = "id;notes\r" + (1..10000).joinToString("") { "$it;${if (it == 50) "target" else "miss"}\r" }
        TableChunkReader(Positional(value.toByteArray()), "data.csv").use { reader ->
            reader.read()
            val previous = reader.findPreviousRows(value.toByteArray().size.toLong(), 10001, "target")
            assertTrue(previous.table.rows.contains(listOf("50", "target")))
            assertEquals(previous.table.rows.size, previous.rowIndices.size)
            assertEquals("false", previous.table.metadata.attributes["complete"])
        }
    }
    @Test fun reverseTextCanTraverseTheWholeSourceWithoutMissingOrRepeatingUnicode() {
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val value = "a🎵日本語é\n".repeat(150)
            val bytes = value.toByteArray(charset)
            TextChunkReader(Positional(bytes), charset).use { reader ->
                var end = bytes.size.toLong(); val windows = mutableListOf<String>()
                while (end > 0) {
                    val previous = reader.readPrevious(end, 17, 3)
                    assertEquals(end, previous.nextOffset); assertTrue(previous.start < end)
                    assertFalse(previous.document.text.contains('\ufffd'))
                    windows.add(0, previous.document.text); end = previous.start
                }
                assertEquals(value, windows.joinToString(""))
            }
        }
    }
    @Test fun reverseCsvMatchesLegacyRowsAcrossSeededRecordsAndRepeatedDirectionChanges() {
        val random = java.util.Random(73)
        repeat(50) {
            val newline = listOf("\n", "\r\n", "\r")[it % 3]
            val rows = listOf(listOf("id", "notes")) + (1..140).map { index ->
                listOf(index.toString(), (1..(1 + random.nextInt(20))).joinToString("") {
                    listOf("é", "🎵", ",", "\"", "\n", "x")[random.nextInt(6)]
                })
            }
            val value = DelimitedText.formatCsv(rows).replace("\r\n", "\n")
            // Change record terminators without changing quoted field contents.
            val encoded = buildString {
                var quoted = false
                value.forEach { char ->
                    if (char == '\"') quoted = !quoted
                    if (char == '\n' && !quoted) append(newline) else append(char)
                }
            }
            val expected = DelimitedText.parseCsv(encoded)
            TableChunkReader(Positional(encoded.toByteArray(), shortRead = 7), "data.csv").use { reader ->
                var next = reader.read(maxRows = 7)
                while (next.hasNext) next = reader.read(next.nextOffset, next.nextRowIndex, maxRows = 7)
                var end = next.nextOffset; var endIndex = next.nextRowIndex
                while (end > 0) {
                    val previous = reader.readPrevious(end, endIndex, maxRows = 7)
                    assertEquals(expected.subList(previous.firstRowIndex.toInt(), endIndex.toInt()), previous.table.rows)
                    val forward = reader.read(previous.start, previous.firstRowIndex, maxRows = previous.table.rows.size)
                    assertEquals(previous.table.rows, forward.table.rows)
                    end = previous.start; endIndex = previous.firstRowIndex
                }
                assertEquals(0L, endIndex)
            }
        }
    }
    @Test fun precedingReadsRemainPositionalBeyondFourGiBAndHonorCancellation() {
        val source = Positional("value\n".toByteArray(), 12L * 1024 * 1024 * 1024)
        TextChunkReader(source).use { reader ->
            val end = 8L * 1024 * 1024 * 1024
            val previous = reader.readPrevious(end)
            assertEquals(end, previous.nextOffset)
            assertTrue(previous.start > end - 16384)
            assertTrue(source.fetched <= 3 * 16384)
            assertTrue(source.positions.all { it >= end - 16384 })
        }
        TableChunkReader(Positional("a,b\n1,2\n".toByteArray()), "data.csv", CancellationToken { true }).use { reader ->
            assertThrows(InterruptedIOException::class.java) { reader.readPrevious(8, 2) }
        }
    }

    @Test fun exactDataRowCountsMatchTheLegacyParserAndExcludeTheHeader() {
        val fixtures = listOf("", "id,name", "id,name\n", "id,name\r\n1,\"first\nsecond\"\r\n\r\n2,\"quote \"\"inside\"\"\"",
            "id;name\r1;one\r\r2;two\r", "id\tnotes\n1\tvalue\n", "id,notes\n1,\"unclosed\nrest")
        for (value in fixtures) {
            val name = if (value.contains('\t')) "data.tsv" else "data.csv"
            val delimiter = DelimitedText.detectDelimiter(value, name)
            val expected = (DelimitedText.parseCsv(value, delimiter).size - 1).coerceAtLeast(0).toLong()
            TableChunkReader(Positional(value.toByteArray(), shortRead = 3), name).use { reader ->
                assertEquals(expected, reader.countDataRows())
            }
        }
    }
    @Test fun countingScansBoundedWindowsWithoutDisturbingTheViewportReader() {
        val value = "id,notes\n" + (1..20000).joinToString("") { "$it,\"line\nquoted \"\"value\"\"\"\n" }
        val viewportSource = Positional(value.toByteArray())
        TableChunkReader(viewportSource, "data.csv").use { viewport ->
            val visible = viewport.read(); val fetched = viewportSource.fetched
            TableChunkReader(InputStreamSource(value.byteInputStream()), "data.csv").use { counter ->
                assertEquals(20000L, counter.countDataRows())
                assertFalse(counter.supportsRandomAccess)
                assertFalse(counter.canRead(0)) // Count went beyond the bounded byte cache.
            }
            assertEquals(fetched, viewportSource.fetched)
            assertEquals(visible.table.rows, viewport.read().table.rows)
        }
    }
    @Test fun rowCountCancellationAndReadFailuresPropagateAndOwnedSourcesClose() {
        val cancelled = Positional("id\n1\n".toByteArray())
        TableChunkReader(cancelled, "data.csv", CancellationToken { true }).use { reader ->
            assertThrows(InterruptedIOException::class.java) { reader.countDataRows() }
        }
        assertTrue(cancelled.closed)
        var closed = false
        val broken = object : ByteSource {
            override val metadata = StreamMetadata()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("count failure")
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { TableChunkReader(broken).use { it.countDataRows() } }
        assertTrue(closed)
    }

}
