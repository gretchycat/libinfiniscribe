package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*

object ScribeCapabilities { val registry get() = RuntimeCapabilities.library("scribe") }

class CoreScribeCapabilityProvider : CapabilityProvider {
    override val library = "scribe"
    override fun handlers(): List<CapabilityHandler> = listOf(
        text("text", FormatSupport(ScribeTextFormats.TEXT_MIME_TYPES + setOf("text/*", "image/x-ansi"), ScribeTextFormats.TEXT_EXTENSIONS), "text"),
        text("markdown", FormatSupport(setOf("text/markdown"), ScribeTextFormats.MARKDOWN_EXTENSIONS), "markdown"),
        text("html", FormatSupport(setOf("text/html","application/xhtml+xml"), ScribeTextFormats.HTML_EXTENSIONS), "html"),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.table",library,"Delimited text",
            listOf(FormatSupport(setOf("text/csv","text/tab-separated-values","application/csv","application/x-csv","text/x-csv","text/comma-separated-values","text/x-comma-separated-values"),setOf("csv","tsv"))),
            setOf(Operations.DECODE,Operations.ENCODE,Operations.EDIT), limitations=listOf("Host must retain complete source/revision before save", "No spreadsheet formula evaluation"),
            attributes=mapOf("presentation" to "spreadsheet")),TableDocuments),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.epub",library,"EPUB",listOf(FormatSupport(setOf("application/epub+zip"),setOf("epub"))),
            setOf(Operations.DECODE,Operations.METADATA),metadata=setOf("title","author","chapters"),
            limitations=listOf("ZIP resources supplied by caller; EPUB subset and expansion budgets apply"),attributes=mapOf("presentation" to "epub")),EpubDocuments),
        ProcessorCapabilityHandler(HandlerDescriptor("scribe.pipeline.table.parse",library,"Text to table",emptyList(),setOf("process")),TableParseProcessor().registration()),
        ProcessorCapabilityHandler(HandlerDescriptor("scribe.pipeline.table.format",library,"Table to text",emptyList(),setOf("process")),TableFormatProcessor().registration()),
        FictionBookHandler(),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.font.inspect",library,"SFNT font inspection",listOf(FormatSupport(FontFormats.mimeTypes,FontFormats.extensions,
            magic=listOf(MagicSignature("00010000"),MagicSignature("4f54544f"),MagicSignature("74746366")))),
            setOf(Operations.METADATA),metadata=setOf("faces","family","glyphCount","axes"),limitations=listOf("64 MiB; SFNT inspection only, rendering needs platform backend")),FontDocuments),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.hex",library,"Byte explorer",emptyList(),setOf(Operations.EDIT,Operations.METADATA),
            limitations=listOf("Positional bytes and known length required; host owns safe publication"),attributes=mapOf("presentation" to "hex")),HexEditorFactory { input,length -> HexDocument(input,length) })
    )
    private fun text(id:String,format:FormatSupport,presentation:String) = ProcessorCapabilityHandler(
        HandlerDescriptor("scribe.$id",library,id,listOf(format),setOf(Operations.DECODE),
            metadata=setOf("text","charset","encodedBytes","completeness"),limitations=listOf("4 MiB default complete-document limit; incomplete preview cannot be saved"),
            priority=if(id=="text")0 else 10,attributes=mapOf("presentation" to presentation)),TextDecodeProcessor().registration())
}

class FictionBookHandler : ReadingCapabilityHandler {
    override val descriptor=HandlerDescriptor("scribe.fb2","scribe","FictionBook",listOf(FormatSupport(setOf("application/x-fictionbook+xml","text/fb2+xml"),setOf("fb2"))),
        setOf(Operations.DECODE,Operations.METADATA),limitations=listOf("Reflowed read-only document; bounded XML/resources"),attributes=mapOf("presentation" to "epub"))
    override fun read(input:ReadingInput)=FictionBooks.read(input.source,input.name,input.output,input.cancellation)
}
