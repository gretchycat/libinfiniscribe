package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import java.io.File

/** Host staging policy is explicit; handlers retain parsing and output generation. */
data class ReadingInputPlan(val expandZip: Boolean = false, val flatXml: Boolean = false)
data class ReadingInput(val source: SourceResource, val localFile: File, val resources: DocumentResources,
    val output: DocumentOutput, val name: String, val mimeType: String?,
    val cancellation: CancellationToken = CancellationToken.NONE)
interface ReadingCapabilityHandler : CapabilityHandler {
    override fun describe()=descriptor.copy(attributes=descriptor.attributes+("presentation" to "epub"))
    fun plan(name: String, mimeType: String?): ReadingInputPlan = ReadingInputPlan()
    fun read(input: ReadingInput): ReadingDocument
}
fun interface HexEditorFactory { fun open(input: PositionalReadable, length: Long): HexDocument }
