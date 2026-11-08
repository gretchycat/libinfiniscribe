package io.github.gretchycat.infiniscribe.mobi

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*

class MobiCapabilityProvider : CapabilityProvider {
    override val library="scribe"
    override fun handlers():List<CapabilityHandler> = listOf(MobiIdentityHandler(),MobiReadingHandler())
}
private val mobiFormats get()=listOf(FormatSupport(
    DocumentFormats.mimeKinds.filterValues{it==DocumentFormats.Kind.MOBI}.keys,DocumentFormats.extensions.filterValues{it==DocumentFormats.Kind.MOBI}.keys,
    magic=listOf(MagicSignature("424f4f4b4d4f4249",60,description="Palm BOOKMOBI record database"))))

private class MobiIdentityHandler : CapabilityHandler {
    override val descriptor=HandlerDescriptor("scribe.mobi.identify","scribe","Mobipocket identity",mobiFormats,
        setOf(Operations.IDENTIFY),limitations=listOf("Palm record table and BOOKMOBI header only; DRM/readability is checked by the MOBI reader"))
    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        if(context.resource.source==null)return super.probe(context)
        return if(DocumentIdentification.mobipocket(context.prefix(),context.resource.metadata.length)) {
            val mime=context.resource.metadata.content.mimeType?.takeUnless { it.endsWith("/*") || it.lowercase() in setOf(
                "application/octet-stream","application/binary","application/x-binary","application/x-unknown") }
                ?: "application/x-mobipocket-ebook"
            HandlerProbe(MatchConfidence.VERIFIED,setOf(Operations.IDENTIFY),"Palm database record table and BOOKMOBI signature validated",mapOf("mimeType" to mime))
        } else HandlerProbe(MatchConfidence.NONE,emptySet())
    }
}
private class MobiReadingHandler : ReadingCapabilityHandler {
    override val descriptor=HandlerDescriptor("scribe.mobi.read","scribe","MOBI/KF8",mobiFormats,
        setOf(Operations.DECODE,Operations.METADATA),limitations=listOf("64 MiB input/resources; DRM unsupported; libmobi required"),attributes=mapOf("presentation" to "epub"))
    override fun availability():HandlerAvailability { Class.forName("io.github.gretchycat.infiniscribe.mobi.MobiNative");return HandlerAvailability() }
    override fun read(input:ReadingInput)=MobiDocuments.read(input.localFile,input.name,input.output,input.cancellation,input.source.metadata)
}
