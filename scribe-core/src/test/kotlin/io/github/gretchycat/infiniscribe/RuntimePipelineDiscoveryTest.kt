package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class RuntimePipelineDiscoveryTest {
    @Test fun discoveredStagesDecodeBytesAndParseARealTableWithoutTheApplication() {
        val processors=RuntimeProcessors.registry(ScribeCapabilities.registry)
        InputStreamSource("name,value\nalpha,42\n".byteInputStream()).use {source->
            val target=TableDocuments.parseDescriptor.outputs.single()
            val route=processors.plan(source.metadata,target)!!
            assertEquals(listOf("scribe.text.decode","scribe.table.parse"),route.map {it.processor.descriptor.id})
            processors.openPipeline(source,target).use {session->
                val table=(session.outputs.getValue("table").single() as TableDocumentHandle).document
                assertEquals(listOf(listOf("name","value"),listOf("alpha","42")),table.rows)
                assertEquals("true",table.metadata.attributes["complete"])
                assertTrue(table.metadata.provenance.transformations.contains("scribe.table.parse"))
            }
        }
    }
}
