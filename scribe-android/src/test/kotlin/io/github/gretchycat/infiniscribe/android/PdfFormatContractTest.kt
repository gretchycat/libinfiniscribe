package io.github.gretchycat.infiniscribe.android

import io.github.gretchycat.infinistream.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFormatContractTest {
    @Test fun pdfModuleExposesTheCommonIdentityThumbnailAndMetadataContract() {
        val handler = AndroidScribeCapabilityProvider().handlers().single { it.descriptor.id == "scribe.pdf.android" }
        val resource = CapabilityResource(StreamMetadata(
            name = "manual.pdf",
            content = ContentType(mimeType = "application/pdf")
        ))
        val normalized = handler.normalize(CapabilityProbeContext(resource))!!

        assertEquals(setOf("application/pdf"), handler.mimeTypes())
        assertEquals(setOf("pdf"), handler.extensions())
        assertEquals(listOf("255044462d"), handler.magicSignatures().map { it.hex })
        assertEquals(ViewerTarget.INFINISCRIBE, normalized.viewerTarget)
        assertTrue(normalized.thumbnailSupported)
        assertTrue(handler.metadataFields().containsAll(setOf("pageCount", "width", "height")))
        assertNull(handler.thumbnail(ThumbnailRequest(resource, 256)))
    }
}
