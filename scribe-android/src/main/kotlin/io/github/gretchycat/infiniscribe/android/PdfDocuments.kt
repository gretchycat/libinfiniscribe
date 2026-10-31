package io.github.gretchycat.infiniscribe.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.github.gretchycat.infiniscribe.PageRasterSize
import io.github.gretchycat.infinistream.*
import java.io.File
import java.io.InterruptedIOException

/** An owned page raster. takeBitmap transfers its lifetime to a presentation surface. */
class PdfPageFrame internal constructor(bitmap: Bitmap, override val metadata: StreamMetadata) : StreamHandle {
    private var image: Bitmap? = bitmap
    val bitmap: Bitmap get() = checkNotNull(image) { "Page frame is closed or transferred" }
    @Synchronized fun takeBitmap(): Bitmap = bitmap.also { image = null }
    @Synchronized override fun close() { image?.recycle(); image = null }
}

/** Android is an explicit backend; callers supply a local, seekable file and retain any staging lease. */
object PdfDocuments {
    @JvmStatic
    @JvmOverloads
    fun open(file: File, input: StreamMetadata = FileSourceResource(file).metadata): PdfDocument {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) {
            try { descriptor.close() } catch (close: Throwable) { error.addSuppressed(close) }
            throw error
        }
        // PdfRenderer owns its descriptor after construction.
        return try { PdfDocument(renderer, input) } catch (error: Throwable) {
            try { renderer.close() } catch (close: Throwable) { error.addSuppressed(close) }
            throw error
        }
    }
}

/** Page open/render/close and document close are serialized, including calls from different workers. */
class PdfDocument internal constructor(private val renderer: PdfRenderer, input: StreamMetadata) : StreamHandle {
    val pageCount: Int = renderer.pageCount
    private var closed = false
    override val metadata = StreamMetadata(ContentType(DataKind.STRUCTURED, "application/pdf", "pdf-pages"),
        name = input.name, finiteness = Finiteness.FINITE, ownership = Ownership.OWNED,
        attributes = mapOf("pageCount" to pageCount.toString(), "backend" to "android-pdf-renderer"),
        provenance = input.provenance.copy(transformations = input.provenance.transformations + "pdf.open"),
        warnings = input.warnings.toList())

    @Synchronized
    @JvmOverloads
    @Throws(java.io.IOException::class)
    fun renderPage(index: Int, viewportWidth: Int, maxDimension: Int = 4096): PdfPageFrame {
        check(!closed) { "PDF document is closed" }
        require(index in 0 until pageCount) { "Page index out of range: $index" }
        checkCancellation()
        return renderer.openPage(index).use { page ->
            val size = PageRasterSize.fit(page.width, page.height, viewportWidth, maxDimension)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                checkCancellation()
                PdfPageFrame(bitmap, StreamMetadata(ContentType(DataKind.IMAGE_FRAME, "image/x-android-bitmap", "android-bitmap"),
                    finiteness = Finiteness.FINITE, ownership = Ownership.OWNED,
                    attributes = mapOf("width" to size.width.toString(), "height" to size.height.toString(),
                        "pixelFormat" to "ARGB_8888", "pageIndex" to index.toString(), "pageCount" to pageCount.toString(),
                        "pageWidth" to page.width.toString(), "pageHeight" to page.height.toString()),
                    provenance = metadata.provenance.copy(transformations = metadata.provenance.transformations + "pdf.render"),
                    warnings = metadata.warnings))
            } catch (error: Throwable) { bitmap.recycle(); throw error }
        }
    }

    @Synchronized override fun close() { if (!closed) { closed = true; renderer.close() } }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PDF rendering cancelled")
    }
}
