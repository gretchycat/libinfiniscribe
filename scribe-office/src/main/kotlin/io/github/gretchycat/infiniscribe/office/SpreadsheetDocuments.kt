package io.github.gretchycat.infiniscribe.office

import io.github.gretchycat.infinistream.CancellationToken
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellReference
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.xssf.eventusermodel.*
import org.apache.poi.xssf.model.StylesTable
import org.apache.poi.xssf.usermodel.XSSFComment
import org.apache.poi.util.XMLHelper
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.Attributes
import java.io.File
import java.io.IOException

/** Actual worksheet row numbers; missing/sparse cells retain their coordinates. */
data class SpreadsheetRow(val number:Long,val cells:List<String>)
data class SpreadsheetSheet(val name:String,val rows:List<SpreadsheetRow>,val columnCount:Int,val limited:Boolean,
    val warnings:List<String> = listOf("Read-only; formula values use the workbook's saved results"))
data class SpreadsheetLimits(val rows:Int=10000,val columns:Int=256,val characters:Int=4*1024*1024,val cellCharacters:Int=32768) {
    init {require(rows>0 && columns>0 && characters>0 && cellCharacters>0)}
}

interface SpreadsheetBook:AutoCloseable {
    val sheetNames:List<String>
    fun sheet(index:Int,cancellation:CancellationToken=CancellationToken.NONE,limits:SpreadsheetLimits=SpreadsheetLimits()):SpreadsheetSheet
}

/** Owned workbook handle; Scribe retains format parsing and cell formatting.
 * Call from one document worker. The host owns source staging and presentation. */
class SpreadsheetDocument private constructor(private val legacy:HSSFWorkbook?,private val xml:OPCPackage?,
    private val relationIds:List<String>,override val sheetNames:List<String>) : SpreadsheetBook {
    private val xmlStyles by lazy {checkNotNull(xml).let {XSSFReader(it).stylesTable}}
    private val xmlStrings by lazy {ReadOnlySharedStringsTable(checkNotNull(xml))}
    @Synchronized
    override fun sheet(index:Int,cancellation:CancellationToken,limits:SpreadsheetLimits):SpreadsheetSheet {
        require(index in sheetNames.indices)
        cancellation.check()
        val rows=ArrayList<SpreadsheetRow>();var columns=0;var characters=0;var limited=false
        fun accept(number:Long,values:List<String>) {
            cancellation.check()
            if(rows.size>=limits.rows || characters>=limits.characters) {limited=true;throw WindowFull()}
            val clipped=values.take(limits.columns).map {value ->
                val remaining=(limits.characters-characters).coerceAtLeast(0)
                val text=value.take(minOf(limits.cellCharacters,remaining))
                if(text.length!=value.length)limited=true
                characters+=text.length;text
            }
            if(values.size>limits.columns)limited=true
            columns=maxOf(columns,clipped.size);rows+=SpreadsheetRow(number,clipped)
        }
        try {
            val formatter=DataFormatter(java.util.Locale.getDefault()).apply {setUseCachedValuesForFormulaCells(true)}
            if(legacy!=null) {
                for(row in legacy.getSheetAt(index)) {
                    cancellation.check()
                    val width=(row.lastCellNum.toInt()).coerceAtLeast(0)
                    if(width>limits.columns)limited=true
                    accept(row.rowNum.toLong()+1,(0 until minOf(width,limits.columns)).map {col ->
                        row.getCell(col)?.let {formatter.formatCellValue(it)}.orEmpty()
                    })
                }
            } else {
                val pkg=checkNotNull(xml)
                val reader=XSSFReader(pkg)
                val styles=xmlStyles
                val strings=xmlStrings
                var rowValues=mutableListOf<String>();var rowNumber=0
                val handler=object:XSSFSheetXMLHandler.SheetContentsHandler {
                    override fun startRow(rowNum:Int) {cancellation.check();rowNumber=rowNum;rowValues=mutableListOf()}
                    override fun endRow(rowNum:Int) {accept(rowNumber.toLong()+1,rowValues)}
                    override fun cell(cellReference:String?,formattedValue:String?,comment:XSSFComment?) {
                        cancellation.check()
                        val col=cellReference?.let {CellReference(it).col.toInt()} ?: rowValues.size
                        if(col>=limits.columns){limited=true;return}
                        while(rowValues.size<=col)rowValues.add("")
                        rowValues[col]=formattedValue.orEmpty()
                    }
                }
                val parser=XMLHelper.newXMLReader()
                parser.contentHandler=XSSFSheetXMLHandler(styles,null,strings,handler,formatter,false)
                reader.getSheet(relationIds[index]).use {parser.parse(InputSource(it))}
            }
        } catch(_:WindowFull) {} catch(error:SAXException) {
            if(error.cause !is WindowFull)throw error
        }
        return SpreadsheetSheet(sheetNames[index],rows,columns,limited)
    }
    @Synchronized override fun close(){legacy?.close();xml?.close()}
    private class WindowFull:SAXException()
    companion object {
        fun open(file:File,cancellation:CancellationToken=CancellationToken.NONE):SpreadsheetDocument {
            OfficeRuntime.check(file);cancellation.check()
            val head=file.inputStream().use {input -> ByteArray(8).also {input.read(it)}}
            if(head[0]==0xd0.toByte() && head[1]==0xcf.toByte()) {
                val book=file.inputStream().use {HSSFWorkbook(it)}
                try {
                    require(book.numberOfSheets<=512){"Too many worksheets"}
                    return SpreadsheetDocument(book,null,emptyList(),(0 until book.numberOfSheets).map {book.getSheetName(it)})
                } catch(error:Throwable){book.close();throw error}
            }
            val pkg=OPCPackage.open(file,PackageAccess.READ)
            try {
                val names=mutableListOf<String>();val ids=mutableListOf<String>()
                val parser=XMLHelper.newXMLReader()
                parser.contentHandler=object:DefaultHandler() {
                    override fun startElement(uri:String?,local:String?,q:String?,attrs:Attributes) {
                        cancellation.check()
                        if((local?.takeIf {it.isNotEmpty()} ?: q.orEmpty().substringAfter(':'))=="sheet") {
                            if(names.size>=512)throw IOException("Too many worksheets")
                            val id=attrs.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id") ?: attrs.getValue("r:id")
                                ?: throw IOException("Worksheet relationship missing")
                            names+=attrs.getValue("name") ?: "Sheet ${names.size+1}";ids+=id
                        }
                    }
                }
                XSSFReader(pkg).workbookData.use {parser.parse(InputSource(it))}
                return SpreadsheetDocument(null,pkg,ids,names)
            }catch(error:Throwable){pkg.close();throw error}
        }
    }
}
