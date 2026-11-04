package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeReparserDiscoveryTest {
    @Test fun installedParserIsDiscoveredAndParsesRealXmlWithoutAnApp() {
        val parser=TextReparserRegistry().detect("example.xml","application/xml","<root><child>value</child></root>")
        assertNotNull(parser)
        val output=parser!!.format("<root><child>value</child></root>",CancellationToken.NONE)
        val document=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.byteInputStream())
        assertEquals("value",document.getElementsByTagName("child").item(0).textContent)
        assertThrows(Exception::class.java){parser.format("<root>",CancellationToken.NONE)}
    }
    @Test fun discoveredCoreTextProcessorProducesARealDocument() {
        val registry=RuntimeCapabilities.library("scribe")
        val entry=registry.probe(CapabilityResource(StreamMetadata(name="notes.txt",content=ContentType(mimeType="text/plain"))),Operations.DECODE)
            .single {it.descriptor.id=="scribe.text"}
        val processor=entry.handler.service(StreamProcessor::class.java)!!
        InputStreamSource("Library consumer".byteInputStream()).use { source ->
            processor.open(mapOf("encoded" to listOf(source))).use { session ->
                val result=session.outputs.getValue("text").single() as io.github.gretchycat.infiniscribe.TextDocumentHandle
                assertEquals("Library consumer",result.document.text);assertFalse(result.document.hasMore)
            }
        }
    }
}
