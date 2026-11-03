package io.github.gretchycat.infiniscribe.office

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*
import org.apache.poi.xwpf.usermodel.*
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.xslf.usermodel.*
import org.apache.poi.hslf.usermodel.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Practical reflowed Office reading. No desktop layout engine, macro execution,
 * formula recalculation or write-back. Sources/output storage remain host-owned. */
object OfficeDocuments {
    private const val MAX_HTML=8*1024*1024
    private const val MAX_RESOURCES=64L*1024*1024
    fun read(file:File,name:String,output:DocumentOutput,cancellation:CancellationToken=CancellationToken.NONE,extensionHint:String?=null,
        input:StreamMetadata=FileSourceResource(file).metadata):ReadingDocument {
        OfficeRuntime.check(file)
        val writer=Writer(output,cancellation)
        val ext=(extensionHint ?: name.substringAfterLast('.')).lowercase()
        val chapters=mutableListOf<ReadingChapter>()
        var title=name;var author=""
        fun chapter(body:String,label:String) {
            cancellation.check()
            val path="document-${chapters.size+1}.html"
            writer.write(path,ReadingHtml.page(label,body).toByteArray(Charsets.UTF_8))
            chapters+=ReadingChapter(path,label)
        }
        when(ext) {
            "docx","docm","dotx","dotm" -> file.inputStream().use {input -> XWPFDocument(input).use {doc ->
                title=doc.properties.coreProperties.title?.takeIf {it.isNotBlank()} ?: name
                author=doc.properties.coreProperties.creator.orEmpty()
                val body=StringBuilder()
                for(header in doc.headerList)body.append(writer.xwpf(header.bodyElements,0))
                body.append(writer.xwpf(doc.bodyElements,0))
                for(footer in doc.footerList)body.append(writer.xwpf(footer.bodyElements,0))
                chapter(body.toString(),title)
            } }
            "doc","dot" -> file.inputStream().use {input -> HWPFDocument(input).use {doc ->
                title=doc.summaryInformation?.title?.takeIf {it.isNotBlank()} ?: name
                author=doc.summaryInformation?.author.orEmpty()
                val range=doc.range;val body=StringBuilder();var index=0
                while(index<range.numParagraphs()) {
                    writer.check(body)
                    val paragraph=range.getParagraph(index)
                    if(paragraph.isInTable) {
                        val table=range.getTable(paragraph)
                        body.append("<table>")
                        for(r in 0 until table.numRows()) {
                            cancellation.check();body.append("<tr>")
                            val row=table.getRow(r)
                            for(c in 0 until row.numCells()) {
                                body.append("<td>");val cell=row.getCell(c)
                                for(p in 0 until cell.numParagraphs())body.append(writer.docParagraph(doc,cell.getParagraph(p)))
                                body.append("</td>")
                            }
                            body.append("</tr>")
                        }
                        body.append("</table>");index+=maxOf(1,table.numParagraphs())
                    } else {body.append(writer.docParagraph(doc,paragraph));index++}
                }
                chapter(body.toString(),title)
            } }
            "pptx","pptm","potx","potm","ppsx","ppsm" -> file.inputStream().use {input -> XMLSlideShow(input).use {show ->
                if(show.slides.size>2000)throw IOException("Too many slides")
                show.slides.forEachIndexed {i,slide ->
                    val label=slide.title?.takeIf {it.isNotBlank()} ?: "Slide ${i+1}"
                    chapter("<h1>${ReadingHtml.escape(label)}</h1>"+writer.xmlShapes(slide.shapes,0),label)
                }
            } }
            "ppt","pot","pps" -> file.inputStream().use {input -> HSLFSlideShow(input).use {show ->
                if(show.slides.size>2000)throw IOException("Too many slides")
                show.slides.forEachIndexed {i,slide ->
                    val label=slide.title?.takeIf {it.isNotBlank()} ?: "Slide ${i+1}"
                    chapter("<h1>${ReadingHtml.escape(label)}</h1>"+writer.legacyShapes(slide.shapes,0),label)
                }
            } }
            else -> throw IOException("Unsupported Office document format: $ext")
        }
        if(chapters.isEmpty())throw IOException("No readable document content found")
        return ReadingDocument(title,author,chapters,StreamMetadata(name=name,
            content=ContentType(DataKind.STRUCTURED,format="reflow-document"),
            warnings=listOf("Reflowed reading view; complex page/slide layout and some embedded objects may differ"),
            provenance=input.provenance.copy(transformations=input.provenance.transformations+"scribe.office.read")))
    }

    private class Writer(val output:DocumentOutput,val cancel:CancellationToken) {
        var bytes=0L
        private val images=mutableMapOf<String,String>()
        fun check(body:StringBuilder) {cancel.check();if(body.length>MAX_HTML)throw IOException("Document content exceeds preview limit")}
        fun write(path:String,data:ByteArray) {
            cancel.check()
            if(data.size>16*1024*1024 || bytes+data.size>MAX_RESOURCES)throw IOException("Document resources exceed reader limit")
            output.open(path).use {sink ->
                var at=0;while(at<data.size){cancel.check();val count=minOf(65536,data.size-at);sink.write(data,at,count);at+=count}
            }
            bytes+=data.size
        }
        fun image(data:ByteArray,extension:String):String {
            cancel.check()
            val ext=extension.lowercase().takeIf {it.matches(Regex("[a-z0-9]{1,8}"))} ?: "bin"
            if(ext !in setOf("png","jpg","jpeg","gif","bmp","svg","webp"))return "<p>[${ReadingHtml.escape(ext.uppercase())} image]</p>"
            val hash=MessageDigest.getInstance("SHA-256").digest(data).joinToString(""){"%02x".format(it.toInt() and 255)}
            val path=images[hash] ?: "image-${images.size+1}.$ext".also {write(it,data);images[hash]=it}
            return "<img src=\"$path\" alt=\"Embedded image\">"
        }
        fun text(value:String)=ReadingHtml.escape(value).replace("\n","<br>").replace("\t","&#9;")
        fun xwpf(elements:List<IBodyElement>,depth:Int):String {
            if(depth>16)throw IOException("Document tables are nested too deeply")
            val body=StringBuilder()
            for(element in elements) {
                check(body)
                when(element) {
                    is XWPFParagraph -> {
                        val heading=Regex("(?i)heading([1-6])").matchEntire(element.style.orEmpty())?.groupValues?.get(1)
                        val tag=heading?.let {"h$it"} ?: "p"
                        body.append("<$tag>")
                        for(run in element.runs) {
                            cancel.check()
                            if(run.isBold)body.append("<b>")
                            if(run.isItalic)body.append("<i>")
                            body.append(text(run.text()))
                            for(picture in run.embeddedPictures)picture.pictureData?.let {data ->body.append(image(data.data,data.suggestFileExtension()))}
                            if(run.isItalic)body.append("</i>")
                            if(run.isBold)body.append("</b>")
                        }
                        body.append("</$tag>")
                    }
                    is XWPFTable -> {
                        body.append("<table>")
                        for(row in element.rows) {
                            cancel.check();body.append("<tr>")
                            for(cell in row.tableCells)body.append("<td>").append(xwpf(cell.bodyElements,depth+1)).append("</td>")
                            body.append("</tr>")
                        }
                        body.append("</table>")
                    }
                }
            }
            check(body);return body.toString()
        }
        fun docParagraph(doc:HWPFDocument,paragraph:Paragraph):String {
            val body=StringBuilder("<p>")
            for(i in 0 until paragraph.numCharacterRuns()) {
                check(body);val run=paragraph.getCharacterRun(i)
                if(run.isBold)body.append("<b>");if(run.isItalic)body.append("<i>")
                val value=run.text().filter {it=='\n'||it=='\t'||it>=' '}.trimEnd('\u0007').replace('\r','\n')
                body.append(text(value))
                if(doc.picturesTable.hasPicture(run))doc.picturesTable.extractPicture(run,false)?.let {picture ->
                    body.append(image(picture.content,picture.suggestFileExtension()))
                }
                if(run.isItalic)body.append("</i>");if(run.isBold)body.append("</b>")
            }
            return body.append("</p>").toString()
        }
        fun xmlShapes(shapes:List<XSLFShape>,depth:Int):String {
            if(depth>16)throw IOException("Slide groups are nested too deeply")
            val body=StringBuilder()
            for(shape in shapes) {
                check(body)
                when(shape) {
                    is XSLFTable -> {body.append("<table>");for(row in shape.rows){body.append("<tr>");for(cell in row.cells)body.append("<td>${text(cell.text)}</td>");body.append("</tr>")};body.append("</table>")}
                    is XSLFTextShape -> body.append("<p>${text(shape.text)}</p>")
                    is XSLFPictureShape -> body.append(image(shape.pictureData.data,shape.pictureData.type.extension.removePrefix(".")))
                    is XSLFGroupShape -> body.append(xmlShapes(shape.shapes,depth+1))
                }
            }
            return body.toString()
        }
        fun legacyShapes(shapes:List<HSLFShape>,depth:Int):String {
            if(depth>16)throw IOException("Slide groups are nested too deeply")
            val body=StringBuilder()
            for(shape in shapes) {
                check(body)
                when(shape) {
                    is HSLFTable -> {body.append("<table>");for(r in 0 until shape.numberOfRows){body.append("<tr>");for(c in 0 until shape.numberOfColumns)body.append("<td>${text(shape.getCell(r,c)?.text.orEmpty())}</td>");body.append("</tr>")};body.append("</table>")}
                    is HSLFTextShape -> body.append("<p>${text(shape.text)}</p>")
                    is HSLFPictureShape -> shape.pictureData?.let {body.append(image(it.data,it.type.extension.removePrefix(".")))}
                    is HSLFGroupShape -> body.append(legacyShapes(shape.shapes,depth+1))
                }
            }
            return body.toString()
        }
    }
}
