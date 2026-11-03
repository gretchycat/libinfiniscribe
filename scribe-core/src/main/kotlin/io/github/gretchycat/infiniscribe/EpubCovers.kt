package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.CancellationToken
import java.io.InterruptedIOException

data class EpubCover(val path: String, val mimeType: String?)

/** Cover metadata only: no chapter parsing, image decoding or container extraction.
 * Resources remain borrowed. Uses the same bounded XML reader as EpubDocuments. */
object EpubCovers {
    @JvmStatic
    @JvmOverloads
    fun candidates(resources: DocumentResources, cancellation: CancellationToken = CancellationToken.NONE,
        limits: EpubReadLimits = EpubReadLimits()): List<EpubCover> {
        val packagePath = EpubDocuments.packagePath(resources, cancellation, limits)
        data class Item(val id: String, val href: String, val mime: String?, val properties: Set<String>)
        val items = linkedMapOf<String, Item>()
        var legacyId: String? = null
        val guide = mutableListOf<String>()
        var manifest = false
        var metadata = false
        var inGuide = false
        EpubDocuments.xml(resources, packagePath, cancellation, limits, start = { name, attrs ->
            when (name) {
                "manifest" -> manifest = true
                "metadata" -> metadata = true
                "guide" -> inGuide = true
                "meta" -> if (metadata && attrs["name"].equals("cover", true)) legacyId = attrs["content"]
                "item" -> if (manifest) {
                    val id = attrs["id"]; val href = attrs["href"]
                    if (!id.isNullOrEmpty() && !href.isNullOrEmpty()) {
                        if (items.size >= limits.maxResourcePaths) throw java.io.IOException("Too many EPUB resources")
                        items[id] = Item(id, href, attrs["media-type"], attrs["properties"].orEmpty().split(Regex("\\s+")).toSet())
                    }
                }
                "reference" -> if (inGuide && "cover" in attrs["type"].orEmpty().split(Regex("\\s+")))
                    attrs["href"]?.let(guide::add)
            }
        }, end = { name ->
            if (name == "manifest") manifest = false
            if (name == "metadata") metadata = false
            if (name == "guide") inGuide = false
        })
        val result = linkedMapOf<String, EpubCover>()
        val pages = linkedSetOf<String>()
        fun add(item: Item) {
            val path = DocumentPaths.resolve(packagePath, item.href) ?: return
            if (item.mime?.startsWith("image/") == true) result[path] = EpubCover(path, item.mime)
            else if (item.mime in setOf("application/xhtml+xml", "text/html")) pages += path
        }
        items.values.filter { "cover-image" in it.properties }.forEach(::add)
        legacyId?.let(items::get)?.let(::add)
        for (href in guide) {
            val path = DocumentPaths.resolve(packagePath, href) ?: continue
            val image = items.values.firstOrNull { DocumentPaths.resolve(packagePath, it.href) == path }
            if (image?.mime?.startsWith("image/") == true) add(image) else pages += path
        }
        // Older generators sometimes name a cover item but omit the formal meta entry.
        items.values.filter { it.id.equals("cover", true) || it.id.equals("cover-image", true) }.forEach(::add)
        for (page in pages.take(4)) try {
            EpubDocuments.xml(resources, page, cancellation, limits, start = { name, attrs ->
                val ref = when (name) { "img" -> attrs["src"]; "image" -> attrs["href"]; else -> null }
                if (ref != null && result.size < 16) DocumentPaths.resolve(page, ref)?.let { path ->
                    val mime = items.values.firstOrNull { DocumentPaths.resolve(packagePath, it.href) == path }?.mime
                    result.putIfAbsent(path, EpubCover(path, mime))
                }
            })
        } catch (error: Exception) {
            cancellation.check()
            if (error is InterruptedIOException) throw error
            // An unreadable legacy cover page does not discard declared image covers.
        }
        return result.values.take(16)
    }
}
