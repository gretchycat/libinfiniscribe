package io.github.gretchycat.infiniscribe.office

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*
import java.io.IOException
import org.xml.sax.SAXException

/** OpenDocument XML content over host/Vault resources. Reflowed text/slides and
 * cached worksheet cells, without external references or Office recalculation. */
object OpenDocuments {
    fun read(resources:DocumentResources,name:String,output:DocumentOutput,cancel:CancellationToken=CancellationToken.NONE):ReadingDocument {
        val source=resources.source("content.xml") ?: throw IOException("OpenDocument content.xml is missing")
        val body=StringBuilder();var inBody=false;val stack=ArrayDeque<String>();val images=linkedSetOf<String>()
        DocumentXml.read(source,cancel,start={tag,attrs ->
            cancel.check();if(body.length>8*1024*1024)throw IOException("Document exceeds reader content limit")
            if(tag=="body")inBody=true
            val html=if(!inBody)"" else when(tag) {
                "p"->"p";"h"->"h${attrs["outline-level"]?.toIntOrNull()?.coerceIn(1,6) ?: 2}"
                "list"->"ul";"list-item"->"li";"table"->"table";"table-row"->"tr";"table-cell","covered-table-cell"->"td"
                "page"->"section";else->""
            }
            stack.addLast(html)
            if(html.isNotEmpty())body.append("<$html>")
            if(inBody)when(tag) {
                "tab"->body.append("&#9;")
                "line-break"->body.append("<br>")
                "s"->repeat(attrs["c"]?.toIntOrNull()?.coerceIn(1,128) ?: 1){body.append("&#160;")}
                "image"->attrs["href"]?.let {ref -> DocumentPaths.resolve("content.xml",ref)?.let {path ->
                    if(images.size>=2000)throw IOException("Too many document images")
                    images+=path;body.append("<img src=\"${ReadingHtml.escape(path)}\" alt=\"Document image\">")
                }}
            }
        },text={if(inBody)body.append(ReadingHtml.escape(it))},end={tag ->
            val html=stack.removeLast();if(html.isNotEmpty())body.append("</$html>")
            if(tag=="body")inBody=false
        })
        var bytes=0L
        for(path in images) {
            cancel.check()
            val image=resources.source(path) ?: continue
            val data=image.open().use {readBytes(it,16*1024*1024,cancel)}
            bytes+=data.size;if(bytes>64L*1024*1024)throw IOException("Document images exceed reader limit")
            output.open(path).use {it.write(data)}
        }
        if(body.isEmpty())throw IOException("No readable OpenDocument content found")
        output.open("document.html").use {it.write(ReadingHtml.page(name,body.toString()).toByteArray(Charsets.UTF_8))}
        return ReadingDocument(name,"",listOf(ReadingChapter("document.html",name)),
            StreamMetadata(name=name,content=ContentType(DataKind.STRUCTURED,format="reflow-document"),
                provenance=resources.metadata.provenance.copy(transformations=resources.metadata.provenance.transformations+"scribe.opendocument.read"),
                warnings=listOf("Reflowed reading view; page/slide layout and embedded objects may differ")))
    }

    fun workbook(resources:DocumentResources,cancellation:CancellationToken=CancellationToken.NONE):SpreadsheetBook {
        val source=resources.source("content.xml") ?: throw IOException("OpenDocument content.xml is missing")
        val names=mutableListOf<String>();var tableDepth=0
        DocumentXml.read(source,cancellation,start={tag,attrs ->if(tag=="table") {
            if(tableDepth++==0){if(names.size>=512)throw IOException("Too many worksheets");names+=attrs["name"] ?: "Sheet ${names.size+1}"}
        }},end={if(it=="table")tableDepth--})
        return object:SpreadsheetBook {
            override val sheetNames=names.toList()
            override fun close()=Unit // Host owns the borrowed resources.
            override fun sheet(index:Int,cancellation:CancellationToken,limits:SpreadsheetLimits):SpreadsheetSheet {
                require(index in sheetNames.indices)
                val rows=mutableListOf<SpreadsheetRow>();var table=-1;var depth=0;var active=false
                var row=mutableListOf<String>();var number=0L;var rowRepeat=1;var cellRepeat=1
                var inCell=false;val text=StringBuilder();var cached="";var formula="";var col=0;var characters=0;var width=0;var limited=false
                fun repeatCount(attrs:Map<String,String>,key:String)=attrs[key]?.toIntOrNull()?.also {if(it !in 1..1048576)throw IOException("Invalid worksheet repeat count")} ?: 1
                try {
                    DocumentXml.read(source,cancellation,start={tag,attrs ->
                        cancellation.check()
                        if(tag=="table") {if(depth++==0){table++;active=table==index}}
                        if(active && depth==1)when(tag) {
                            "table-row"->{row=mutableListOf();col=0;rowRepeat=repeatCount(attrs,"number-rows-repeated")}
                            "table-cell","covered-table-cell"->{inCell=true;text.setLength(0);cellRepeat=repeatCount(attrs,"number-columns-repeated");cached=attrs["string-value"] ?: attrs["date-value"] ?: attrs["time-value"] ?: attrs["boolean-value"] ?: attrs["value"].orEmpty();formula=attrs["formula"].orEmpty()}
                            "tab"->if(inCell)text.append('\t')
                            "s"->if(inCell)repeat(attrs["c"]?.toIntOrNull()?.coerceIn(1,128) ?: 1){text.append(' ')}
                        }
                    },text={if(active && depth==1 && inCell){if(text.length+it.length>limits.cellCharacters)limited=true;text.append(it.take((limits.cellCharacters-text.length).coerceAtLeast(0)))}},end={tag ->
                        if(active && depth==1)when(tag) {
                            "p"->if(inCell && text.isNotEmpty())text.append('\n')
                            "table-cell","covered-table-cell"->{
                                val value=text.toString().trimEnd('\n').ifEmpty {cached.ifEmpty {formula}}
                                val count=minOf(cellRepeat,(limits.columns-col).coerceAtLeast(0))
                                repeat(count){row+=value}
                                if(cellRepeat>count && value.isNotEmpty())limited=true
                                col=(col.toLong()+cellRepeat).coerceAtMost(Int.MAX_VALUE.toLong()).toInt();inCell=false
                            }
                            "table-row"->{
                                if(row.any {it.isNotEmpty()})repeat(rowRepeat) {
                                    if(rows.size>=limits.rows || characters>=limits.characters){limited=true;throw SheetDone()}
                                    val clipped=row.map {value ->val result=value.take(minOf(limits.cellCharacters,(limits.characters-characters).coerceAtLeast(0)));characters+=result.length;if(result.length!=value.length)limited=true;result}
                                    rows+=SpreadsheetRow(number+1,clipped);number++;width=maxOf(width,clipped.size)
                                } else number+=rowRepeat
                            }
                        }
                        if(tag=="table"){if(active && depth==1)throw SheetDone();depth--}
                    })
                }catch(_:SheetDone){}
                return SpreadsheetSheet(sheetNames[index],rows,width,limited)
            }
        }
    }
    private class SheetDone:SAXException()
}
