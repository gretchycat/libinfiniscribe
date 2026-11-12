package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.Operations
import io.github.gretchycat.infinistream.ViewerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceViewerCapabilitiesTest {
    @Test fun plainSourceAlternatesKeepTheCanonicalImageMimeAndDeclareTheirViewer() {
        val handlers = CoreScribeCapabilityProvider().handlers().associateBy { it.descriptor.id }

        listOf(
            "scribe.source.xpm" to ("xpm" to "image/x-xpixmap"),
            "scribe.source.xcf" to ("xcf" to "image/x-xcf")
        ).forEach { (id, claim) ->
            val handler = handlers.getValue(id)
            assertEquals(setOf(claim.first), handler.extensions())
            assertEquals(setOf(claim.second), handler.mimeTypes())
            assertEquals(ViewerTarget.INFINISCRIBE, handler.viewerTarget())
            assertTrue(Operations.DECODE in handler.describe().operations)
            assertEquals("plain", handler.describe().attributes["sourceMode"])
        }
    }

    @Test fun ansiPlainSourceIsAdvertisedSeparatelyForTheBackupViewer() {
        val handler = CoreScribeCapabilityProvider().handlers().single { it.descriptor.id == "scribe.source.ansi" }

        assertEquals(setOf("ans", "ansi", "asc"), handler.extensions())
        assertTrue(handler.mimeTypes().containsAll(setOf("text/x-ansi", "image/x-ansi", "text/x-ascii-art")))
        assertEquals(ViewerTarget.INFINISCRIBE, handler.viewerTarget())
        assertEquals("ansi", handler.describe().attributes["sourceMode"])
    }
}
