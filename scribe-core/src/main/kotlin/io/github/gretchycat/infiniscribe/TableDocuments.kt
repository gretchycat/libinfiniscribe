package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import java.io.IOException

/** In-memory Unicode cells. Collections follow Stream's immutable snapshot contract. */
data class TableDocument(val rows: List<List<String>>, val delimiter: Char, val metadata: StreamMetadata)

class TableDocumentHandle(val document: TableDocument) : StreamHandle {
    override val metadata get() = document.metadata
    override fun close() {}
}

object TableDocuments {
    val parseDescriptor = ProcessorDescriptor("scribe.table.parse",
        listOf(PortDescriptor("text", setOf(DataKind.TEXT), attributes = mapOf("storage" to "text-document"))),
        listOf(PortDescriptor("table", setOf(DataKind.STRUCTURED), attributes = mapOf("storage" to "table-document"))))
    val formatDescriptor = ProcessorDescriptor("scribe.table.format",
        listOf(PortDescriptor("table", setOf(DataKind.STRUCTURED), attributes = mapOf("storage" to "table-document"))),
        listOf(PortDescriptor("text", setOf(DataKind.TEXT), attributes = mapOf("storage" to "text-document"))))

    /** Parse a decoded preview; original encoded bytes remain in the caller's TextDocument for revision checks. */
    fun parse(document: TextDocument, fileName: String = "", delimiter: Char? = null,
        cancellation: CancellationToken = CancellationToken.NONE): TableDocument {
        cancellation.check()
        val selected = delimiter ?: DelimitedText.detectDelimiter(document.text, fileName)
        val processor = TableParseProcessor(selected)
        val registry = ProcessorRegistry(listOf(processor.registration()))
        registry.openPipeline(TextDocumentHandle(document), parseDescriptor.outputs.single(),
            ProcessorOptions(cancellation = cancellation)).use { session ->
            return (session.outputs.getValue("table").single() as TableDocumentHandle).document
        }
    }

    /** Serialize current cells with the existing quoting/LF rules and UTF-8 save bytes. No storage writes occur here. */
    fun format(document: TableDocument, cancellation: CancellationToken = CancellationToken.NONE): TextDocument {
        val processor = TableFormatProcessor()
        val registry = ProcessorRegistry(listOf(processor.registration()))
        registry.openPipeline(TableDocumentHandle(document), formatDescriptor.outputs.single(),
            ProcessorOptions(cancellation = cancellation)).use { session ->
            return (session.outputs.getValue("text").single() as TextDocumentHandle).document
        }
    }

    internal fun tableMetadata(source: StreamMetadata, delimiter: Char): StreamMetadata {
        val format = when (delimiter) { ',' -> "csv"; '\t' -> "tsv"; else -> "delimited-text" }
        val mime = when (delimiter) { ',' -> "text/csv"; '\t' -> "text/tab-separated-values"; else -> "text/plain" }
        return source.copy(content = ContentType(DataKind.STRUCTURED, mime, format, "unicode"),
            length = null, finiteness = Finiteness.FINITE, capabilities = emptySet(),
            ownership = Ownership.OWNED, concurrency = Concurrency.SERIAL,
            attributes = source.attributes.filterKeys { it in setOf("complete", "sourceCharset", "decoding") } +
                mapOf("storage" to "table-document", "delimiter" to delimiter.toString(), "parsing" to "legacy-viewer-csv"),
            provenance = source.provenance.copy(transformations = source.provenance.transformations + "scribe.table.parse"),
            verification = if (source.attributes["complete"] == "false") Verification.PROVISIONAL else Verification.UNKNOWN)
    }

    internal fun textMetadata(source: StreamMetadata, delimiter: Char): StreamMetadata = source.copy(
        content = ContentType(DataKind.TEXT,
            when (delimiter) { ',' -> "text/csv"; '\t' -> "text/tab-separated-values"; else -> "text/plain" },
            when (delimiter) { ',' -> "csv"; '\t' -> "tsv"; else -> "delimited-text" }, "unicode"), length = null,
        finiteness = Finiteness.FINITE, capabilities = emptySet(), ownership = Ownership.OWNED, concurrency = Concurrency.SERIAL,
        attributes = source.attributes.filterKeys { it in setOf("complete", "delimiter") } + mapOf(
            "complete" to (source.attributes["complete"] != "false").toString(),
            "storage" to "text-document", "charset" to "UTF-8", "sourceCharset" to "UTF-8", "lineEnding" to "LF", "delimiter" to delimiter.toString()),
        provenance = source.provenance.copy(transformations = source.provenance.transformations + "scribe.table.format"),
        verification = if (source.attributes["complete"] == "false") Verification.PROVISIONAL else Verification.UNKNOWN)
}

class TableParseProcessor(private val delimiter: Char = ',') : StreamProcessor {
    override val descriptor = TableDocuments.parseDescriptor
    fun registration()=ProcessorRegistration(this) {inputs,_ ->
        mapOf("table" to listOf(TableDocuments.tableMetadata(inputs.getValue("text").single(),delimiter)))
    }
    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        if (options.mode != FlowMode.PULL || options.values.isNotEmpty()) throw IOException("Unsupported table parser options")
        val document = (inputs["text"]?.singleOrNull() as? TextDocumentHandle)?.document
            ?: throw IOException("One decoded text document is required")
        val rows = DelimitedText.parseCsv(document.text, delimiter, options.cancellation)
        val metadata = TableDocuments.tableMetadata(document.metadata, delimiter).let { metadata ->
            metadata.copy(attributes = metadata.attributes + mapOf("rowCount" to rows.size.toString(),
                "columnCount" to (rows.maxOfOrNull { it.size } ?: 0).toString(), "complete" to (!document.hasMore).toString()),
                verification = if (document.hasMore) Verification.PROVISIONAL else Verification.UNKNOWN)
        }
        val value = TableDocumentHandle(TableDocument(rows, delimiter, metadata))
        return object : ProcessorSession {
            override val outputs = mapOf("table" to listOf<StreamHandle>(value))
            override fun close() = value.close()
        }
    }
}

class TableFormatProcessor : StreamProcessor {
    override val descriptor = TableDocuments.formatDescriptor
    fun registration()=ProcessorRegistration(this) {inputs,_ ->
        val source=inputs.getValue("table").single()
        val delimiter=source.attributes["delimiter"]?.singleOrNull()?:','
        mapOf("text" to listOf(TableDocuments.textMetadata(source,delimiter)))
    }
    override fun open(inputs: Map<String, List<StreamHandle>>, options: ProcessorOptions): ProcessorSession {
        if (options.mode != FlowMode.PULL || options.values.isNotEmpty()) throw IOException("Unsupported table formatter options")
        val table = (inputs["table"]?.singleOrNull() as? TableDocumentHandle)?.document
            ?: throw IOException("One table document is required")
        val text = DelimitedText.formatCsv(table.rows, table.delimiter, options.cancellation)
        val metadata = TableDocuments.textMetadata(table.metadata, table.delimiter).let { metadata ->
            metadata.copy(attributes = metadata.attributes + mapOf("utf16CodeUnits" to text.length.toString(),
                "rowCount" to table.rows.size.toString(), "columnCount" to (table.rows.maxOfOrNull { it.size } ?: 0).toString()))
        }
        val value = TextDocumentHandle(TextDocument(text, text.toByteArray(Charsets.UTF_8),
            table.metadata.attributes["complete"] == "false", metadata))
        return object : ProcessorSession {
            override val outputs = mapOf("text" to listOf<StreamHandle>(value))
            override fun close() = value.close()
        }
    }
}
