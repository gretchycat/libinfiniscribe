package io.github.gretchycat.infiniscribe.android

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*
import java.io.File
import java.net.URI

class AndroidScribeCapabilityProvider : CapabilityProvider {
    override val library="scribe"
    override fun handlers():List<CapabilityHandler> = listOf(
        PdfFormatHandler(HandlerDescriptor("scribe.pdf.android",library,"Android PDF",listOf(FormatSupport(setOf("application/pdf"),setOf("pdf"),magic=listOf(MagicSignature("255044462d")))),
            setOf(Operations.IDENTIFY,Operations.VIEW,Operations.METADATA,Operations.THUMBNAIL),metadata=setOf("pageCount","width","height"),limitations=listOf("Local seekable file required; read-only; Android PdfRenderer format limits"),
            attributes=mapOf("presentation" to "pdf"))),
        ServiceCapabilityHandler(HandlerDescriptor("scribe.font.android",library,"Android SFNT fonts",listOf(FormatSupport(FontFormats.mimeTypes,FontFormats.extensions)),
            setOf(Operations.VIEW),limitations=listOf("Read-only TTF/OTF/TTC/OTC; device font support applies; 64 MiB"),attributes=mapOf("presentation" to "font")),FontTypefaces,runtime=::androidAvailability)
    )
}

/** Common PDF identity, first-page thumbnail, and basic page metadata adapter. */
private class PdfFormatHandler(descriptor: HandlerDescriptor) : ServiceCapabilityHandler(descriptor, PdfDocuments, ::androidAvailability) {
    override fun thumbnail(request: ThumbnailRequest): ThumbnailResult? {
        request.cancellation.check()
        val file = request.resource.localFile() ?: return null
        return try {
            PdfDocuments.open(file, request.resource.metadata).use { document ->
                if (document.pageCount == 0) return@use null
                val page = document.renderPage(0, request.targetPixels, request.targetPixels)
                try {
                    request.cancellation.check()
                    ThumbnailResult.frame(page)
                } catch (error: Throwable) {
                    page.close()
                    throw error
                }
            }
        } catch (cancel: java.io.InterruptedIOException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    override fun extractMetadata(context: MetadataReadContext): MetadataRecord? {
        context.cancellation.check()
        val resource = context.resource
        val file = resource.localFile() ?: return null
        return PdfDocuments.open(file, resource.metadata).use { document ->
            val page = if (document.pageCount > 0) document.pageSize(0) else null
            context.cancellation.check()
            MetadataRecord(descriptor.id, "application/pdf", fields = buildMap {
                put("pageCount", document.pageCount.toString())
                page?.let {
                    put("width", it.first.toString())
                    put("height", it.second.toString())
                }
            })
        }
    }
}

private fun CapabilityResource.localFile(): File? {
    return try {
        val sourceUri = URI(this.uri ?: return null)
        if (!sourceUri.scheme.equals("file", ignoreCase = true)) return null
        File(sourceUri)
    } catch (_: Exception) { null }
}

private fun androidAvailability()=if(android.os.Build.VERSION.SDK_INT>=26)HandlerAvailability()
    else HandlerAvailability(AvailabilityState.UNAVAILABLE,"Android document/font runtime unavailable")
