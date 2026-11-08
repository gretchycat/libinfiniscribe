package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*

object ScribeCapabilities { val registry get() = RuntimeCapabilities.library("scribe") }

class CoreScribeCapabilityProvider : CapabilityProvider {
    override val library = "scribe"
    override fun handlers(): List<CapabilityHandler> = listOf(
        PlainTextContentHandler(),
        text("text", FormatSupport(ScribeTextFormats.TEXT_MIME_TYPES + setOf("text/*", "image/x-ansi"), ScribeTextFormats.TEXT_EXTENSIONS), "text"),
        text("markdown", FormatSupport(setOf("text/markdown"), ScribeTextFormats.MARKDOWN_EXTENSIONS), "markdown"),
        text("html", FormatSupport(setOf("text/html","application/xhtml+xml"), ScribeTextFormats.HTML_EXTENSIONS), "html"),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.table",library,"Delimited text",
            listOf(FormatSupport(setOf("text/csv","text/tab-separated-values","application/csv","application/x-csv","text/x-csv","text/comma-separated-values","text/x-comma-separated-values"),setOf("csv","tsv"))),
            setOf(Operations.DECODE,Operations.ENCODE,Operations.EDIT), limitations=listOf("Host must retain complete source/revision before save", "No spreadsheet formula evaluation"),
            attributes=mapOf("presentation" to "spreadsheet")),TableDocuments),
        EpubCapabilityHandler(),
        ProcessorCapabilityHandler(HandlerDescriptor("scribe.pipeline.table.parse",library,"Text to table",emptyList(),setOf("process")),TableParseProcessor().registration()),
        ProcessorCapabilityHandler(HandlerDescriptor("scribe.pipeline.table.format",library,"Table to text",emptyList(),setOf("process")),TableFormatProcessor().registration()),
        FictionBookHandler(),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.font.inspect",library,"SFNT font inspection",listOf(FormatSupport(FontFormats.mimeTypes,FontFormats.extensions,
            magic=listOf(MagicSignature("00010000"),MagicSignature("4f54544f"),MagicSignature("74746366")))),
            setOf(Operations.IDENTIFY,Operations.METADATA),metadata=setOf("faces","family","glyphCount","axes"),limitations=listOf("64 MiB; SFNT inspection only, rendering needs platform backend")),FontDocuments),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.hex",library,"Byte explorer",emptyList(),setOf(Operations.EDIT,Operations.METADATA),
            limitations=listOf("Positional bytes and known length required; host owns safe publication"),attributes=mapOf("presentation" to "hex")),HexEditorFactory { input,length -> HexDocument(input,length) })
    )
    private fun text(id:String,format:FormatSupport,presentation:String) = ProcessorCapabilityHandler(
        HandlerDescriptor("scribe.$id",library,id,listOf(format),setOf(Operations.DECODE),
            metadata=setOf("text","charset","encodedBytes","completeness"),limitations=listOf("4 MiB default complete-document limit; incomplete preview cannot be saved"),
            priority=if(id=="text")0 else 10,attributes=mapOf("presentation" to presentation)),TextDecodeProcessor().registration())
}

/** Fallback for unassociated files. Extension and MIME remain hints; content must look like text. */
private class PlainTextContentHandler : CapabilityHandler {
    override val descriptor = HandlerDescriptor(
        "scribe.text.content", "scribe", "Plain text by content",
        listOf(FormatSupport(setOf("application/octet-stream"))),
        setOf(Operations.IDENTIFY, Operations.DECODE),
        metadata = setOf("charset", "evidence"),
        limitations = listOf("Uses a bounded prefix; text classification is heuristic"),
        priority = -100,
        attributes = mapOf("presentation" to "text", "contentValidation" to "printable-text-prefix")
    )

    override fun probe(context: CapabilityProbeContext): HandlerProbe {
        // Do not make every unknown binary file appear supported during metadata-only routing.
        if (context.resource.source == null) return HandlerProbe(MatchConfidence.NONE, emptySet())
        val result = PlainTextIdentification.detect(context.prefix())
            ?: return HandlerProbe(MatchConfidence.NONE, emptySet())
        return HandlerProbe(MatchConfidence.VERIFIED, setOf(Operations.IDENTIFY, Operations.DECODE),
            "Content is readable as plain text", mapOf("mimeType" to "text/plain", "charset" to result.charset.name(), "evidence" to result.evidence))
    }
}

class FictionBookHandler : ReadingCapabilityHandler {
    override val descriptor=HandlerDescriptor("scribe.fb2","scribe","FictionBook",listOf(FormatSupport(setOf("application/x-fictionbook+xml","text/fb2+xml"),setOf("fb2"))),
        setOf(Operations.IDENTIFY,Operations.DECODE,Operations.METADATA),limitations=listOf("Reflowed read-only document; bounded XML/resources"),attributes=mapOf("presentation" to "epub"))
    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        if(context.resource.source==null)return super<ReadingCapabilityHandler>.probe(context)
        return if(DocumentIdentification.fictionBook(context.prefix())) HandlerProbe(MatchConfidence.VERIFIED,
            setOf(Operations.IDENTIFY),"FictionBook XML root parsed",mapOf("mimeType" to "application/x-fictionbook+xml"))
        else HandlerProbe(MatchConfidence.NONE,emptySet())
    }
    override fun read(input:ReadingInput)=FictionBooks.read(input.source,input.name,input.output,input.cancellation)
}

private class EpubCapabilityHandler : ServiceCapabilityHandler(
    HandlerDescriptor("scribe.epub","scribe","EPUB",listOf(FormatSupport(setOf("application/epub+zip"),setOf("epub"),
        magic=listOf(MagicSignature("504b0304",description="ZIP local file header; EPUB mimetype entry validated")))),
        setOf(Operations.IDENTIFY,Operations.DECODE,Operations.METADATA),metadata=setOf("title","author","chapters"),
        limitations=listOf("ZIP resources supplied by caller; EPUB subset and expansion budgets apply",
            "Encrypted chapters are not decrypted; encrypted font/image resources do not by themselves block readable chapters"),attributes=mapOf("presentation" to "epub")), EpubDocuments) {
    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        if(context.resource.source==null)return super.probe(context)
        return if(DocumentIdentification.epub(context.prefix())) HandlerProbe(MatchConfidence.VERIFIED,
            setOf(Operations.IDENTIFY),"Stored EPUB mimetype entry validated",mapOf("mimeType" to "application/epub+zip"))
        else HandlerProbe(MatchConfidence.NONE,emptySet())
    }
}
