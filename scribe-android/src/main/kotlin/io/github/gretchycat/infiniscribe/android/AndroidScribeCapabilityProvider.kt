package io.github.gretchycat.infiniscribe.android

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*

class AndroidScribeCapabilityProvider : CapabilityProvider {
    override val library="scribe"
    override fun handlers():List<CapabilityHandler> = listOf(
        ServiceCapabilityHandler(HandlerDescriptor("scribe.pdf.android",library,"Android PDF",listOf(FormatSupport(setOf("application/pdf"),setOf("pdf"),magic=listOf(MagicSignature("255044462d")))),
            setOf(Operations.VIEW,Operations.METADATA,Operations.THUMBNAIL),metadata=setOf("pageCount","width","height"),limitations=listOf("Local seekable file required; read-only; Android PdfRenderer format limits"),
            attributes=mapOf("presentation" to "pdf")),PdfDocuments,runtime=::androidAvailability),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.font.android",library,"Android SFNT fonts",listOf(FormatSupport(FontFormats.mimeTypes,FontFormats.extensions)),
            setOf(Operations.VIEW),limitations=listOf("Read-only TTF/OTF/TTC/OTC; device font support applies; 64 MiB"),attributes=mapOf("presentation" to "font")),FontTypefaces,runtime=::androidAvailability)
    )
}

private fun androidAvailability()=if(android.os.Build.VERSION.SDK_INT>=26)HandlerAvailability()
    else HandlerAvailability(AvailabilityState.UNAVAILABLE,"Android document/font runtime unavailable")
