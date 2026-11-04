package io.github.gretchycat.infiniscribe.rtf

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*

class RtfCapabilityProvider : CapabilityProvider {
    override val library="scribe"
    override fun handlers():List<CapabilityHandler> = listOf(RtfReadingHandler())
}
private class RtfReadingHandler : ReadingCapabilityHandler {
    override val descriptor=HandlerDescriptor("scribe.rtf.read","scribe","RTF",listOf(FormatSupport(setOf("application/rtf","text/rtf"),setOf("rtf"),magic=listOf(MagicSignature("7b5c727466")))),
        setOf(Operations.DECODE),limitations=listOf("Read-only text reflow; embedded objects/pictures are not implemented"),attributes=mapOf("presentation" to "epub"))
    override fun read(input:ReadingInput)=RtfDocuments.read(input.source,input.name,input.output,input.cancellation)
}
