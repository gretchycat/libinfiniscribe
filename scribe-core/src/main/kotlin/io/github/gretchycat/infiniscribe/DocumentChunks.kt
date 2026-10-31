package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Bounded cache over real readAt, or a forward-only source. Never spools or invents seek support. */
internal class DocumentByteChunks(private val source: ByteSource, private val cancellation: CancellationToken,
    private val ownsSource: Boolean, private val blockSize: Int = 16 * 1024) : Closeable {
    val metadata get() = source.metadata
    val positional = source as? PositionalReadable
    private val cache = object : LinkedHashMap<Long, ByteArray>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 8
    }
    private var sequentialPosition = 0L
    private var closed = false
    fun canRead(position: Long) = positional != null || position >= sequentialPosition || cache.containsKey(position / blockSize * blockSize)
    fun byteAt(position: Long): Int {
        if (closed) throw IOException("Document source is closed")
        require(position >= 0)
        cancellation.check()
        if (metadata.length?.let { position >= it } == true) return -1
        val start = position / blockSize * blockSize
        val bytes = cache[start] ?: load(start).also { cache[start] = it }
        val index = (position - start).toInt()
        return if (index < bytes.size) bytes[index].toInt() and 255 else -1
    }
    private fun load(start: Long): ByteArray {
        if (positional == null && start < sequentialPosition) throw IOException("This stream cannot seek to an evicted chunk")
        while (positional == null && sequentialPosition < start) {
            val skip = ByteArray(minOf(blockSize.toLong(), start - sequentialPosition).toInt())
            val n = read(sequentialPosition, skip, 0, skip.size)
            if (n == -1) return byteArrayOf()
            sequentialPosition += n
        }
        val buffer = ByteArray(blockSize); var count = 0
        while (count < buffer.size) {
            val n = read(start + count, buffer, count, buffer.size - count)
            if (n == -1) break
            count += n
            if (positional == null) sequentialPosition += n
        }
        return buffer.copyOf(count)
    }
    private fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        cancellation.check()
        val n = positional?.readAt(position, buffer, offset, length) ?: source.read(buffer, offset, length)
        if (n == 0 || n < -1 || n > length) throw IOException("Document input returned an invalid read count: $n")
        return n
    }
    fun bytes(start: Long, length: Int): ByteArray {
        val out = ByteArray(length); var count = 0
        while (count < length) { val b = byteAt(start + count); if (b < 0) break; out[count++] = b.toByte() }
        return out.copyOf(count)
    }
    override fun close() { if (!closed) { closed = true; cache.clear(); if (ownsSource) source.close() } }
}

data class TextChunk(val document: TextDocument, val start: Long, val nextOffset: Long, val hasNext: Boolean)

/** Decode one independently addressable byte window; UTF-8/UTF-16 characters are not split between windows. */
class TextChunkReader(source: ByteSource, val charset: Charset = Charsets.UTF_8,
    private val cancellation: CancellationToken = CancellationToken.NONE, ownsSource: Boolean = true) : Closeable {
    private val chunks = DocumentByteChunks(source, cancellation, ownsSource)
    val descriptor = ProcessorDescriptor("scribe.text.chunk",
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES, DataKind.TEXT), capabilities = setOf(Capability.READ))),
        listOf(PortDescriptor("text", setOf(DataKind.TEXT), attributes = mapOf("storage" to "text-document"))),
        options = setOf("offset", "maxBytes", "maxLines", "charset"))
    val sourceMetadata get() = chunks.metadata
    val supportsRandomAccess get() = chunks.positional != null
    fun canRead(offset: Long) = chunks.canRead(offset)
    @Synchronized fun read(offset: Long = 0, maxBytes: Int = 16 * 1024, maxLines: Int = 256): TextChunk {
        require(offset >= 0 && maxBytes in 8..(64 * 1024) && maxLines in 1..4096)
        cancellation.check()
        var start = offset
        val name = charset.name().uppercase()
        if (name == "UTF-8" && start > 0) {
            while (chunks.byteAt(start) in 0x80..0xbf) start++
        } else if (name == "UTF-16LE" || name == "UTF-16BE") {
            if (start % 2 != 0L) start++
            val a = chunks.byteAt(start); val b = chunks.byteAt(start + 1)
            val unit = if (a < 0 || b < 0) -1 else if (name == "UTF-16LE") a or (b shl 8) else (a shl 8) or b
            if (unit in 0xdc00..0xdfff && start > 0) start += 2
        }
        val bytes = chunks.bytes(start, maxBytes)
        val eof = chunks.byteAt(start + bytes.size) == -1
        var byteLimit = bytes.size; var lines = 0
        if (name == "UTF-16LE" || name == "UTF-16BE") {
            var i = 0
            while (i + 1 < bytes.size) {
                val unit = if (name == "UTF-16LE") (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
                    else ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255)
                if (unit == 10 && ++lines == maxLines) { byteLimit = i + 2; break }
                i += 2
            }
        } else {
            for (i in bytes.indices) if (bytes[i] == 10.toByte() && ++lines == maxLines) { byteLimit = i + 1; break }
        }
        val input = ByteBuffer.wrap(bytes, 0, byteLimit); val output = CharBuffer.allocate(maxBytes * 2)
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        decoder.decode(input, output, eof).let { if (it.isError) it.throwException() }
        if (eof) decoder.flush(output)
        val consumed = input.position()
        if (consumed == 0 && bytes.isNotEmpty()) throw IOException("Cannot decode a document window with ${charset.name()}")
        val next = start + consumed; val hasNext = chunks.byteAt(next) != -1
        output.flip(); val text = output.toString(); val whole = start == 0L && !hasNext
        val source = sourceMetadata
        val metadata = source.copy(content = ContentType(DataKind.TEXT, "text/plain", "text", "unicode"),
            length = null, finiteness = Finiteness.FINITE, capabilities = emptySet(), ownership = Ownership.OWNED,
            concurrency = Concurrency.SERIAL, verification = if (whole) Verification.UNKNOWN else Verification.PROVISIONAL,
            attributes = mapOf("storage" to "text-document", "complete" to whole.toString(), "charset" to charset.name(),
                "sourceCharset" to charset.name(), "sourceOffset" to start.toString(), "nextOffset" to next.toString(),
                "access" to if (supportsRandomAccess) "positional" else "sequential", "utf16CodeUnits" to text.length.toString()),
            provenance = source.provenance.copy(offset = start, length = consumed.toLong(),
                transformations = source.provenance.transformations + "scribe.text.chunk"),
            warnings = source.warnings + if (whole) emptyList() else listOf("Document window is not the complete source"))
        return TextChunk(TextDocument(text, bytes.copyOf(consumed), !whole, metadata), start, next, hasNext)
    }
    /** Decode the adjacent preceding window; end at the known character boundary without rescanning the prefix. */
    @Synchronized fun readPrevious(beforeOffset: Long, maxBytes: Int = 16 * 1024, maxLines: Int = 256): TextChunk {
        require(beforeOffset > 0 && maxBytes in 8..(64 * 1024) && maxLines in 1..4096)
        val name = charset.name().uppercase()
        var start = maxOf(0L, beforeOffset - maxBytes)
        val step = if (name == "UTF-16LE" || name == "UTF-16BE") 2 else 1
        var position = beforeOffset - step
        var lines = 0
        // A final LF belongs to the preceding chunk, rather than an empty extra window.
        while (position >= start) {
            val newline = if (step == 1) chunks.byteAt(position) == 10 else {
                val a = chunks.byteAt(position); val b = chunks.byteAt(position + 1)
                if (name == "UTF-16LE") a == 10 && b == 0 else a == 0 && b == 10
            }
            if (newline && position + step != beforeOffset && ++lines == maxLines) { start = position + step; break }
            position -= step
        }
        // read() aligns a partial leading UTF sequence. The end is an existing reader boundary.
        return read(start, (beforeOffset - start).toInt().coerceAtLeast(8), 4096).let { result ->
            if (result.nextOffset != beforeOffset) {
                // Tiny final windows must not consume bytes beyond the requested boundary.
                val bytes = chunks.bytes(result.start, (beforeOffset - result.start).toInt())
                val text = String(bytes, charset)
                val metadata = result.document.metadata.copy(attributes = result.document.metadata.attributes + mapOf(
                    "complete" to "false", "nextOffset" to beforeOffset.toString(), "utf16CodeUnits" to text.length.toString()),
                    provenance = result.document.metadata.provenance.copy(length = bytes.size.toLong()), verification = Verification.PROVISIONAL)
                TextChunk(TextDocument(text, bytes, true, metadata), result.start, beforeOffset, chunks.byteAt(beforeOffset) != -1)
            } else result
        }
    }
    /** Explicit forward search scans windows with bounded memory, including matches across a window edge. */
    @Synchronized fun findNext(query: String, offset: Long, previous: TextChunk? = null): TextChunk? {
        require(query.isNotEmpty())
        var position = offset; var prior = previous
        while (true) {
            cancellation.check()
            val current = read(position)
            val before = prior
            if (before != null && before.nextOffset == current.start) {
                val tail = before.document.text.takeLast(query.length)
                val boundary = tail + current.document.text.take(query.length)
                val match = boundary.indexOf(query, startIndex = (tail.length - query.length + 1).coerceAtLeast(0), ignoreCase = true)
                if (match >= 0 && match < tail.length && match + query.length > tail.length) {
                    val start = maxOf(before.start, before.nextOffset - 32 * 1024)
                    return read(start, 64 * 1024, 1024)
                }
            }
            if (current.document.text.contains(query, ignoreCase = true)) return current
            if (!current.hasNext) return null
            prior = current; position = current.nextOffset
        }
    }
    @Synchronized override fun close() = chunks.close()
}

data class TableChunk @JvmOverloads constructor(val table: TableDocument, val rowIndices: List<Long>, val start: Long,
    val nextOffset: Long, val nextRowIndex: Long, val hasNext: Boolean, val originalBytes: ByteArray?,
    val firstRowIndex: Long = rowIndices.firstOrNull() ?: nextRowIndex)

/** UTF-8 CSV/TSV windows end at real record boundaries, including escaped quotes and quoted newlines. */
class TableChunkReader(source: ByteSource, private val fileName: String = "",
    private val cancellation: CancellationToken = CancellationToken.NONE, ownsSource: Boolean = true) : Closeable {
    private val chunks = DocumentByteChunks(source, cancellation, ownsSource)
    private var selectedDelimiter: Char? = null
    var firstRow: List<String>? = null
        private set
    val descriptor = ProcessorDescriptor("scribe.table.chunk",
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES, DataKind.TEXT), capabilities = setOf(Capability.READ))),
        listOf(PortDescriptor("table", setOf(DataKind.STRUCTURED), attributes = mapOf("storage" to "table-document"))),
        options = setOf("recordOffset", "firstRowIndex", "maxRows", "filter", "maxScanBytes"))
    val sourceMetadata get() = chunks.metadata
    val supportsRandomAccess get() = chunks.positional != null
    fun canRead(offset: Long) = chunks.canRead(offset)
    @Synchronized fun read(offset: Long = 0, firstRowIndex: Long = 0, maxRows: Int = 50,
        filter: String = "", maxScanBytes: Int = 64 * 1024): TableChunk {
        require(offset >= 0 && firstRowIndex >= 0 && maxRows in 1..500 && maxScanBytes in 1024..(1024 * 1024))
        cancellation.check()
        if (offset != 0L && selectedDelimiter == null) throw IOException("Read the first CSV chunk before using record offsets")
        val rows = mutableListOf<List<String>>(); val indices = mutableListOf<Long>()
        val original = if (offset == 0L) ByteArrayOutputStream() else null
        var position = offset; var rowIndex = firstRowIndex
        while (rows.size < maxRows && position - offset < maxScanBytes && chunks.byteAt(position) != -1) {
            val record = ByteArrayOutputStream(); var quoted = false
            while (true) {
                val b = chunks.byteAt(position)
                if (b < 0) break
                record.write(b); position++
                if (b == 34) quoted = !quoted
                if (!quoted && (b == 13 || b == 10)) {
                    if (b == 13 && chunks.byteAt(position) == 10) { record.write(10); position++ }
                    break
                }
                if (record.size() >= 8 * 1024 * 1024) throw IOException("A CSV record exceeds the existing 8 MiB document budget")
            }
            val bytes = record.toByteArray(); original?.write(bytes)
            val text = String(bytes, Charsets.UTF_8)
            if (selectedDelimiter == null) selectedDelimiter = DelimitedText.detectDelimiter(text, fileName)
            for (row in DelimitedText.parseCsv(text, selectedDelimiter!!, cancellation)) {
                if (rowIndex == 0L) firstRow = row
                if (rowIndex == 0L || filter.isEmpty() || row.any { it.contains(filter, ignoreCase = true) }) {
                    rows += row; indices += rowIndex
                }
                rowIndex++
            }
        }
        val more = chunks.byteAt(position) != -1
        val whole = offset == 0L && !more && filter.isEmpty()
        val delimiter = selectedDelimiter ?: DelimitedText.detectDelimiter("", fileName)
        val source = sourceMetadata
        val metadata = TableDocuments.tableMetadata(source, delimiter).copy(
            attributes = mapOf("storage" to "table-document", "complete" to whole.toString(), "sourceCharset" to "UTF-8",
                "sourceOffset" to offset.toString(), "nextOffset" to position.toString(), "rowCount" to rows.size.toString(),
                "columnCount" to (rows.maxOfOrNull { it.size } ?: 0).toString(), "delimiter" to delimiter.toString(),
                "access" to if (supportsRandomAccess) "positional" else "sequential"),
            provenance = source.provenance.copy(offset = offset, length = position - offset,
                transformations = source.provenance.transformations + "scribe.table.chunk"),
            verification = if (whole) Verification.UNKNOWN else Verification.PROVISIONAL,
            warnings = source.warnings + if (whole) emptyList() else listOf("Table window is not the complete source"))
        return TableChunk(TableDocument(rows, delimiter, metadata), indices, offset, position, rowIndex, more,
            if (whole) original!!.toByteArray() else null, firstRowIndex)
    }
    /** Walk records backward from a known outside-quote boundary, with bounded reads and no global index. */
    @Synchronized fun readPrevious(beforeOffset: Long, endRowIndex: Long, maxRows: Int = 50,
        filter: String = ""): TableChunk {
        require(beforeOffset > 0 && endRowIndex >= 0 && maxRows in 1..500)
        cancellation.check()
        val delimiter = selectedDelimiter ?: throw IOException("Read the first CSV window before moving backward")
        var position = beforeOffset; var rowIndex = endRowIndex
        val records = mutableListOf<Pair<Long, List<String>>>()
        while (position > 0 && records.size < maxRows && beforeOffset - position < 64 * 1024) {
            cancellation.check()
            var cursor = position
            if (chunks.byteAt(cursor - 1) == 10) { cursor--; if (cursor > 0 && chunks.byteAt(cursor - 1) == 13) cursor-- }
            else if (chunks.byteAt(cursor - 1) == 13) cursor--
            var quoted = false
            while (cursor > 0) {
                val b = chunks.byteAt(cursor - 1)
                if (b == 34) quoted = !quoted
                if (!quoted && (b == 10 || b == 13)) break
                cursor--
                if (position - cursor > 8L * 1024 * 1024) throw IOException("A CSV record exceeds the existing 8 MiB document budget")
            }
            val bytes = chunks.bytes(cursor, (position - cursor).toInt())
            val parsed = DelimitedText.parseCsv(String(bytes, Charsets.UTF_8), delimiter, cancellation)
            rowIndex -= parsed.size
            if (rowIndex < 0) throw IOException("CSV record cursor no longer matches the source")
            parsed.forEachIndexed { index, row ->
                val number = rowIndex + index
                if (number == 0L) firstRow = row
                if (number == 0L || filter.isEmpty() || row.any { it.contains(filter, ignoreCase = true) }) records.add(0, number to row)
            }
            position = cursor
        }
        val rows = records.map { it.second }; val source = sourceMetadata
        val more = chunks.byteAt(beforeOffset) != -1
        val whole = position == 0L && !more && filter.isEmpty()
        val metadata = TableDocuments.tableMetadata(source, delimiter).copy(
            attributes = mapOf("storage" to "table-document", "complete" to whole.toString(), "sourceCharset" to "UTF-8",
                "sourceOffset" to position.toString(), "nextOffset" to beforeOffset.toString(), "rowCount" to rows.size.toString(),
                "columnCount" to (rows.maxOfOrNull { it.size } ?: 0).toString(), "delimiter" to delimiter.toString(),
                "access" to if (supportsRandomAccess) "positional" else "sequential"),
            provenance = source.provenance.copy(offset = position, length = beforeOffset - position,
                transformations = source.provenance.transformations + "scribe.table.chunk"),
            verification = if (whole) Verification.UNKNOWN else Verification.PROVISIONAL,
            warnings = source.warnings + if (whole) emptyList() else listOf("Table window is not the complete source"))
        return TableChunk(TableDocument(rows, delimiter, metadata), records.map { it.first }, position, beforeOffset,
            endRowIndex, more, if (whole) chunks.bytes(position, (beforeOffset - position).toInt()) else null, rowIndex)
    }
    @Synchronized fun findPreviousRows(beforeOffset: Long, endRowIndex: Long, filter: String = ""): TableChunk {
        var chunk = readPrevious(beforeOffset, endRowIndex, filter = filter)
        while (filter.isNotEmpty() && chunk.start > 0 && chunk.rowIndices.none { it != 0L }) {
            chunk = readPrevious(chunk.start, chunk.firstRowIndex, filter = filter)
        }
        return chunk
    }
    /** A sparse filter scans successive bounded windows rather than allocating the whole source. */
    @Synchronized fun findRows(offset: Long = 0, firstRowIndex: Long = 0, filter: String = ""): TableChunk {
        var chunk = read(offset, firstRowIndex, filter = filter)
        while (filter.isNotEmpty() && chunk.hasNext && chunk.rowIndices.none { it != 0L }) {
            cancellation.check()
            chunk = read(chunk.nextOffset, chunk.nextRowIndex, filter = filter)
        }
        return chunk
    }
    @Synchronized override fun close() = chunks.close()
}
