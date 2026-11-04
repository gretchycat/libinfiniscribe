package io.github.gretchycat.infiniscribe.mobi

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*

class MobiCapabilityProvider : CapabilityProvider {
    override val library="scribe"
    override fun handlers():List<CapabilityHandler> = listOf(MobiReadingHandler())
}
private class MobiReadingHandler : ReadingCapabilityHandler {
    override val descriptor=HandlerDescriptor("scribe.mobi.read","scribe","MOBI/KF8",listOf(FormatSupport(
        DocumentFormats.mimeKinds.filterValues{it==DocumentFormats.Kind.MOBI}.keys,DocumentFormats.extensions.filterValues{it==DocumentFormats.Kind.MOBI}.keys)),
        setOf(Operations.DECODE,Operations.METADATA),limitations=listOf("64 MiB input/resources; DRM unsupported; libmobi required"),attributes=mapOf("presentation" to "epub"))
    override fun availability():HandlerAvailability { Class.forName("io.github.gretchycat.infiniscribe.mobi.MobiNative");return HandlerAvailability() }
    override fun read(input:ReadingInput)=MobiDocuments.read(input.localFile,input.name,input.output,input.cancellation,input.source.metadata)
}
