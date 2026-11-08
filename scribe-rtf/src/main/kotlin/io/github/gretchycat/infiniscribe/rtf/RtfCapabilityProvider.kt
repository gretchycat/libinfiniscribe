package io.github.gretchycat.infiniscribe.rtf

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*

class RtfCapabilityProvider : CapabilityProvider {
    override val library="scribe"
    override fun handlers():List<CapabilityHandler> = listOf(RtfReadingHandler())
}
private class RtfReadingHandler : ReadingCapabilityHandler {
    override val descriptor=HandlerDescriptor("scribe.rtf.read","scribe","RTF",listOf(FormatSupport(setOf("application/rtf","text/rtf"),setOf("rtf"),magic=listOf(MagicSignature("7b5c727466")))),
        setOf(Operations.IDENTIFY,Operations.DECODE),limitations=listOf("Read-only text reflow; embedded objects/pictures are not implemented"),attributes=mapOf("presentation" to "epub"))
    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        if(context.resource.source==null)return super<ReadingCapabilityHandler>.probe(context)
        return if(DocumentIdentification.richText(context.prefix())) HandlerProbe(MatchConfidence.VERIFIED,setOf(Operations.IDENTIFY),
            "RTF control header parsed",mapOf("mimeType" to "application/rtf")) else HandlerProbe(MatchConfidence.NONE,emptySet())
    }
    override fun read(input:ReadingInput)=RtfDocuments.read(input.source,input.name,input.output,input.cancellation)
}
