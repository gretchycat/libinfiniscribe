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
    fun escape(text:String)=buildString {for(c in text)append(when(c){'&'->"&amp;";'<'->"&lt;";'>'->"&gt;";'\"'->"&quot;";'\''->"&#39;";else->c.toString()})}
    fun page(title:String,body:String)="<!doctype html><html><head><meta charset=\"utf-8\"><title>${escape(title)}</title><style>body{font-family:sans-serif;line-height:1.5;overflow-wrap:anywhere}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #888;padding:.35em;vertical-align:top}img{max-width:100%;height:auto}pre{white-space:pre-wrap}</style></head><body>$body</body></html>"
}
