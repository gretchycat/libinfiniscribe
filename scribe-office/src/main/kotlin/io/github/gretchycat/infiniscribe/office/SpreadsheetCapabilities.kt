package io.github.gretchycat.infiniscribe.office

import io.github.gretchycat.infiniscribe.*
import io.github.gretchycat.infinistream.*

/** Spreadsheet components consume this service rather than a filename switch. */
interface SpreadsheetCapabilityHandler : CapabilityHandler {
    override fun describe()=descriptor.copy(attributes=descriptor.attributes+("presentation" to "spreadsheet"))
    fun plan(name:String,mimeType:String?):ReadingInputPlan = ReadingInputPlan()
    fun open(input:ReadingInput):SpreadsheetBook
}
class SpreadsheetCapabilityProvider : CapabilityProvider {
    override val library="scribe"
    override fun handlers():List<CapabilityHandler> = listOf(WorkbookHandler(false),WorkbookHandler(true))
}
private class WorkbookHandler(private val odf:Boolean) : SpreadsheetCapabilityHandler {
    private val kind=if(odf)DocumentFormats.Kind.ODF_SHEET else DocumentFormats.Kind.SHEET
    override val descriptor=HandlerDescriptor(if(odf)"scribe.odf.sheet"else "scribe.office.sheet","scribe",if(odf)"OpenDocument sheet"else "Excel sheet",
        listOf(FormatSupport(DocumentFormats.mimeKinds.filterValues { it==kind }.keys,DocumentFormats.extensions.filterValues { it==kind }.keys)),
        setOf(Operations.DECODE),metadata=setOf("sheetNames","rows","columns","warnings"),
        limitations=listOf("Read-only; bounded rows/cells; saved formula results only"),attributes=mapOf("presentation" to "spreadsheet"))
    override fun plan(name:String,mimeType:String?):ReadingInputPlan {
        val flat=DocumentFormats.canonicalExtension(name,mimeType)=="fods"
        return ReadingInputPlan(odf&&!flat,flat)
    }
    override fun open(input:ReadingInput):SpreadsheetBook=if(odf)OpenDocuments.workbook(input.resources,input.cancellation)
        else SpreadsheetDocument.open(input.localFile,input.cancellation)
}
