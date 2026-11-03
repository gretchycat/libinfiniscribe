package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import java.io.IOException
import java.util.Base64

/** FictionBook 2 metadata, prose, sections, tables and embedded images.
 * XML interpretation stays in Scribe; the host supplies source/output leases. */
object FictionBooks {
    private const val MAX_HTML=8*1024*1024
    fun read(source:SourceResource,name:String,output:DocumentOutput,cancellation:CancellationToken=CancellationToken.NONE):ReadingDocument {
        var title=name;val author=StringBuilder();val body=StringBuilder()
        var bodyDepth=0;var titleInfo=false;var authorDepth=0
        var capture:String?=null;val value=StringBuilder()
        var binaryId:String?=null;var binaryMime="";val binary=StringBuilder()
        val imagePaths=mutableMapOf<String,String>();val references=mutableListOf<Pair<String,String>>()
        val stack=ArrayDeque<String>();var bytes=0L;var coverId:String?=null;var inCover=false
        DocumentXml.read(source,cancellation,start={tag,attrs ->
            cancellation.check()
            if(body.length>MAX_HTML)throw IOException("Book content exceeds reader limit")
            var html=""
            when(tag) {
                "title-info"->titleInfo=true
                "author"->if(titleInfo)authorDepth++
                "book-title"->if(titleInfo){capture=tag;value.setLength(0)}
                "first-name","middle-name","last-name"->if(authorDepth>0){capture=tag;value.setLength(0)}
                "coverpage"->inCover=true
                "body"->{bodyDepth++;html="section"}
                "binary"->{binaryId=attrs["id"];binaryMime=attrs["content-type"].orEmpty();binary.setLength(0)}
            }
            if(bodyDepth>0)html=when(tag){"body","section"->"section";"title"->"h2";"subtitle"->"h3";"p"->"p";"strong"->"b";"emphasis"->"i";"strikethrough"->"s";"sup","sub","table","tr","td","th"->tag;"poem","stanza","cite","epigraph"->"blockquote";"v"->"p";else->html}
            if(tag=="image") {
                val id=attrs["href"]?.removePrefix("#")
                if(inCover && coverId==null)coverId=id
                if(bodyDepth>0 && id!=null){val token="\u0000FB2_IMAGE_${references.size}_END\u0000";references+=token to id;body.append(token)}
            }
            if(bodyDepth>0 && tag=="empty-line")body.append("<br>")
            stack.addLast(html)
            if(html.isNotEmpty())body.append("<$html>")
        },text={text ->
            if(binaryId!=null){if(binary.length+text.length>24*1024*1024)throw IOException("Book image exceeds reader limit");binary.append(text)}
            else if(capture!=null)value.append(text)
            else if(bodyDepth>0){body.append(ReadingHtml.escape(text));if(body.length>MAX_HTML)throw IOException("Book content exceeds reader limit")}
        },end={tag ->
            val html=stack.removeLast()
            if(html.isNotEmpty())body.append("</$html>")
            if(capture==tag) {
                if(tag=="book-title")title=value.toString().trim().ifBlank {name}
                else {if(author.isNotEmpty())author.append(' ');author.append(value.toString().trim())}
                capture=null
            }
            if(tag=="author" && authorDepth>0){authorDepth--;if(author.isNotEmpty())author.append(", ")}
            if(tag=="title-info")titleInfo=false
            if(tag=="coverpage")inCover=false
            if(tag=="body")bodyDepth--
            if(tag=="binary") {
                val id=binaryId
                if(id!=null && binaryMime.startsWith("image/") && imagePaths.size<2000) {
                    val data=try{Base64.getMimeDecoder().decode(binary.toString())}catch(e:IllegalArgumentException){throw IOException("Invalid embedded book image",e)}
                    bytes+=data.size
                    if(data.size>16*1024*1024 || bytes>64L*1024*1024)throw IOException("Book images exceed reader limit")
                    val ext=when(binaryMime){"image/jpeg"->"jpg";"image/png"->"png";"image/gif"->"gif";"image/svg+xml"->"svg";else->null}
                    if(ext!=null){val path="fb2-image-${imagePaths.size+1}.$ext";output.open(path).use {it.write(data)};imagePaths[id]=path}
                }
                binaryId=null;binary.setLength(0)
            }
        })
        var content=body.toString()
        if(content.isBlank())throw IOException("No readable FictionBook content found")
        for((token,id) in references)content=content.replace(token,imagePaths[id]?.let {"<img src=\"$it\" alt=\"Book image\">"} ?: "[Unavailable image]")
        output.open("book.html").use {it.write(ReadingHtml.page(title,content).toByteArray(Charsets.UTF_8))}
        return ReadingDocument(title,author.toString().trim().trimEnd(','),listOf(ReadingChapter("book.html",title)),
            StreamMetadata(name=name,content=ContentType(DataKind.STRUCTURED,format="reflow-document"),
                provenance=source.metadata.provenance.copy(transformations=source.metadata.provenance.transformations+"scribe.fb2.read")),coverId?.let(imagePaths::get))
    }
    /** Bounded cover-only metadata path for icon hosts; no chapter/output creation. */
    fun cover(source:SourceResource,cancellation:CancellationToken=CancellationToken.NONE):ByteArray? {
        var inCover=false;var coverId:String?=null;var binary=false;val data=StringBuilder();var result:ByteArray?=null
        try {
            DocumentXml.read(source,cancellation,start={tag,attrs ->
                if(tag=="coverpage")inCover=true
                if(inCover && tag=="image")coverId=attrs["href"]?.removePrefix("#")
                if(tag=="binary")binary=coverId!=null && attrs["id"]==coverId
            },text={if(binary){if(data.length+it.length>24*1024*1024)throw IOException("Book cover exceeds reader limit");data.append(it)}},end={tag ->
                if(tag=="coverpage")inCover=false
                if(tag=="binary" && binary) {
                    result=Base64.getMimeDecoder().decode(data.toString())
                    if(result!!.size>16*1024*1024)throw IOException("Book cover exceeds reader limit")
                    throw CoverReady()
                }
            })
        }catch(_:CoverReady){}
        return result
    }
    private class CoverReady:org.xml.sax.SAXException()

}
