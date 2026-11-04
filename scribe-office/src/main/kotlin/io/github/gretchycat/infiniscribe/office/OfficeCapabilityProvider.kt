package io.github.gretchycat.infiniscribe.office

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*

class OfficeCapabilityProvider : CapabilityProvider {
    override val library="scribe"
    override fun handlers():List<CapabilityHandler> = listOf(OfficeReadingHandler(false),OfficeReadingHandler(true))
}
private class OfficeReadingHandler(private val openDocument:Boolean) : ReadingCapabilityHandler {
    private val kinds=if(openDocument)setOf(DocumentFormats.Kind.ODF_TEXT,DocumentFormats.Kind.ODF_SLIDES)else setOf(DocumentFormats.Kind.WORD,DocumentFormats.Kind.SLIDES)
    override val descriptor=HandlerDescriptor(if(openDocument)"scribe.odf.read"else "scribe.office.read","scribe",if(openDocument)"OpenDocument"else "Office",
        listOf(FormatSupport(DocumentFormats.mimeKinds.filterValues{it in kinds}.keys,DocumentFormats.extensions.filterValues{it in kinds}.keys)),
        setOf(Operations.DECODE,Operations.METADATA),limitations=listOf("Read-only reflow, no macros or exact page layout; bounded source/output resources"),attributes=mapOf("presentation" to "epub"))
    override fun plan(name:String,mimeType:String?):ReadingInputPlan {
        val ext=DocumentFormats.canonicalExtension(name,mimeType)
        val flat=ext in setOf("fodt","fodp")
        return ReadingInputPlan(expandZip=openDocument&&!flat,flatXml=flat)
    }
    override fun read(input:ReadingInput):ReadingDocument = if(openDocument)OpenDocuments.read(input.resources,input.name,input.output,input.cancellation)
        else OfficeDocuments.read(input.localFile,input.name,input.output,input.cancellation,DocumentFormats.canonicalExtension(input.name,input.mimeType),input.source.metadata)
}
