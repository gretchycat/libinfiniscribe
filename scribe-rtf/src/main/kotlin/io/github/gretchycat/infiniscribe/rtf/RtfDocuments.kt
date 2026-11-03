package io.github.gretchycat.infiniscribe.rtf

import com.rtfparserkit.converter.text.StreamTextConverter
import com.rtfparserkit.parser.RtfStreamSource
import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*
import java.io.*

/** Unicode/text RTF reading through RTF Parser Kit. Rich page layout, embedded
 * objects and pictures are not claimed by this text-conversion backend. */
object RtfDocuments {
    fun read(source:SourceResource,name:String,output:DocumentOutput,cancellation:CancellationToken=CancellationToken.NONE):ReadingDocument {
        val text=ByteArrayOutputStream()
        val target=object:OutputStream() {
            override fun write(value:Int)=write(byteArrayOf(value.toByte()),0,1)
            override fun write(data:ByteArray,offset:Int,length:Int) {
                cancellation.check()
                if(text.size()+length>8*1024*1024)throw IOException("RTF text exceeds the reader limit")
                text.write(data,offset,length)
            }
        }
        source.open().asInputStream().use {raw ->
            val input=object:FilterInputStream(raw) {
                var bytes=0L
                override fun read():Int {val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
                override fun read(data:ByteArray,offset:Int,length:Int):Int {
                    cancellation.check();val count=super.read(data,offset,length)
                    if(count>0)bytes+=count
                    if(bytes>32L*1024*1024)throw IOException("RTF source exceeds the reader limit")
                    return count
                }
            }.buffered()
            input.mark(8)
            val header=ByteArray(5);var at=0
            while(at<header.size){val read=input.read(header,at,header.size-at);if(read<=0)throw IOException("Invalid RTF document");at+=read}
            if(header.toString(Charsets.US_ASCII)!="{\\rtf")throw IOException("Invalid RTF document")
            input.reset()
            try {StreamTextConverter().convert(RtfStreamSource(input),target,"UTF-8")}
            catch(error:RuntimeException){throw(error.cause as? IOException ?: IOException("Cannot read RTF: ${error.message}",error))}
        }
        cancellation.check()
        val html=ReadingHtml.page(name,"<pre>${ReadingHtml.escape(text.toString("UTF-8"))}</pre>")
        output.open("document.html").use {it.write(html.toByteArray(Charsets.UTF_8))}
        return ReadingDocument(name,"",listOf(ReadingChapter("document.html",name)),StreamMetadata(name=name,
            content=ContentType(DataKind.STRUCTURED,format="reflow-document"),
            provenance=source.metadata.provenance.copy(transformations=source.metadata.provenance.transformations+"scribe.rtf.read"),
            warnings=listOf("RTF text view; page layout and embedded images are not displayed")))
    }
}
