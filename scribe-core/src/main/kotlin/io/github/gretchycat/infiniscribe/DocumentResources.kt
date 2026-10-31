package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/** Borrowed related resources. Paths are normalized, case-sensitive package paths, not storage URIs. */
interface DocumentResources : StreamHandle {
    fun paths(): Sequence<String>
    fun source(path: String): SourceResource?
}

object DocumentPaths {
    /** Resolve a URI reference within a package; decode once and retain literal '+' characters. */
    fun resolve(baseFile: String, reference: String): String? {
        if (reference.contains('\\') || reference.contains('\u0000')) return null
        val raw = reference.substringBefore('#').substringBefore('?')
        if (raw.startsWith('/') || runCatching { URI(raw).isAbsolute }.getOrDefault(false)) return null
        val decoded = try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { return null }
        if (decoded.startsWith('/') || decoded.contains('\\') || decoded.contains('\u0000')) return null
        val combined = if (raw.isEmpty()) baseFile else baseFile.substringBeforeLast('/', "").let {
            if (it.isEmpty()) decoded else "$it/$decoded"
        }
        val parts = mutableListOf<String>()
        for (part in combined.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }
}

/** The host owns the directory/cache lease. Opening a resource never stages or downloads anything. */
class DirectoryDocumentResources(private val directory: File, input: StreamMetadata = StreamMetadata()) : DocumentResources {
    private val root = directory.canonicalFile
    override val metadata = StreamMetadata(ContentType(DataKind.DIRECTORY, format = "document-resources"),
        name = input.name, finiteness = Finiteness.FINITE, ownership = Ownership.BORROWED,
        attributes = mapOf("storage" to "document-resources"), provenance = input.provenance,
        warnings = input.warnings.toList())

    override fun paths(): Sequence<String> = root.walkTopDown().onEnter { safe(it) && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile && safe(it) }
        .map { it.relativeTo(root).invariantSeparatorsPath }

    override fun source(path: String): SourceResource? {
        // This API accepts a normalized path, not a second URI reference to decode.
        if (path.isEmpty() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
            path.split('/').any { it == ".." || it == "." }) return null
        val file = File(root, path)
        if (!safe(file) || !file.isFile) return null
        val delegate = FileSourceResource(file)
        val snapshot = delegate.metadata.copy(provenance = metadata.provenance.copy(
            transformations = metadata.provenance.transformations + "document-resource:$path"), warnings = metadata.warnings)
        return object : SourceResource {
            override val metadata = snapshot
            override fun open(): ByteSource {
                if (!safe(file) || !file.isFile) throw IOException("Document resource is unavailable: $path")
                val source = delegate.open()
                return object : ByteSource, PositionalReadable {
                    override val metadata = source.metadata.copy(provenance = snapshot.provenance, warnings = snapshot.warnings)
                    override fun read(buffer: ByteArray, offset: Int, length: Int) = source.read(buffer, offset, length)
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) =
                        (source as PositionalReadable).readAt(position, buffer, offset, length)
                    override fun close() = source.close()
                }
            }
        }
    }
    private fun safe(file: File): Boolean = file.canonicalFile.toPath().startsWith(root.toPath())
    override fun close() {} // Borrowed directory; the host retains its lease.
}
