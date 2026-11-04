package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*

/** Host-owned output resources. Opening a normalized relative path must retain
 * the host's capacity, cancellation and path-confinement policy. */
fun interface DocumentOutput { fun open(path: String): ByteSink }
data class ReadingChapter(val path: String, val title: String)
data class ReadingDocument(val title: String, val author: String, val chapters: List<ReadingChapter>,
    val metadata: StreamMetadata, val coverPath: String? = null)

/** Shared bounded XML adapter for compound-document metadata/content. */
object DocumentXml {
    fun read(source: SourceResource, cancellation: CancellationToken = CancellationToken.NONE,
        limits: EpubReadLimits = EpubReadLimits(maxXmlBytes=32L*1024*1024,maxXmlEvents=2000000,
            maxXmlText=16L*1024*1024,maxXmlDepth=128,maxResourcePaths=10000),
        start: (String, Map<String,String>)->Unit = {_,_->}, text:(String)->Unit = {}, end:(String)->Unit = {}) {
        val resources=object:DocumentResources {
            override val metadata=source.metadata
            override fun source(path:String)=source
            override fun paths()=sequenceOf("document.xml")
            override fun close()=Unit
        }
        EpubDocuments.xml(resources,"document.xml",cancellation,limits,start,text,end)
    }
}

object ReadingHtml {
    /** Defaults used only when a readable chapter has no publisher stylesheet. */
    val fallbackStyle = """
        body{font-family:serif;line-height:1.6;max-width:52rem;margin:auto;overflow-wrap:anywhere}
        chapter,section,part,para,paragraph{display:block}p,para,paragraph{margin:.8em 0}
        h1,h2,h3,h4{line-height:1.25;margin:1.4em 0 .6em}blockquote{margin:1em 1.5em}
        table{border-collapse:collapse;max-width:100%}td,th{padding:.35em;vertical-align:top}
        img,svg{max-width:100%;height:auto}pre{white-space:pre-wrap}
    """.trimIndent()

    /** Preserve XML-declared chapter encoding when the host serves XHTML as HTML. */
    fun declaredEncoding(prefix: ByteArray): String? {
        val bytes = prefix.take(4096).map { it.toInt() and 255 }
        if (bytes.take(4) in listOf(listOf(0xff, 0xfe, 0, 0), listOf(60, 0, 0, 0))) return "UTF-32LE"
        if (bytes.take(4) in listOf(listOf(0, 0, 0xfe, 0xff), listOf(0, 0, 0, 60))) return "UTF-32BE"
        if (bytes.take(3) == listOf(0xef, 0xbb, 0xbf)) return "UTF-8"
        if (bytes.take(2) == listOf(0xff, 0xfe)) return "UTF-16LE"
        if (bytes.take(2) == listOf(0xfe, 0xff)) return "UTF-16BE"
        if (bytes.take(4) == listOf(0, 60, 0, 63)) return "UTF-16BE"
        if (bytes.take(4) == listOf(60, 0, 63, 0)) return "UTF-16LE"
        val header = prefix.copyOf(minOf(prefix.size, 4096)).toString(Charsets.ISO_8859_1)
        val encoding = Regex("""^\s*<\?xml\s+[^?]*\bencoding\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1) ?: return null
        return runCatching { java.nio.charset.Charset.forName(encoding).name() }.getOrNull()
    }
    fun escape(text:String)=buildString {for(c in text)append(when(c){'&'->"&amp;";'<'->"&lt;";'>'->"&gt;";'\"'->"&quot;";'\''->"&#39;";else->c.toString()})}
    fun page(title:String,body:String)="<!doctype html><html><head><meta charset=\"utf-8\"><title>${escape(title)}</title><style>body{font-family:sans-serif;line-height:1.5;overflow-wrap:anywhere}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #888;padding:.35em;vertical-align:top}img{max-width:100%;height:auto}pre{white-space:pre-wrap}</style></head><body>$body</body></html>"
}
