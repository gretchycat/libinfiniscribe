package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.CancellationToken
import org.yaml.snakeyaml.Yaml
import org.junit.Assert.*
import org.junit.Test

class YamlTextReparserTest {
    @Test fun preservesDocumentsAliasesCommentsAndScalarSpellings() {
        val input = "# keep\nfirst: &item {name: 'yes', value: 1.2300}\nsecond: *item\nlist: [one, two]\n---\nlast: value\n"
        val out = YamlTextReparser.format(input, CancellationToken.NONE)
        assertEquals(Yaml().loadAll(input).toList(), Yaml().loadAll(out).toList())
        assertTrue(out.contains("# keep"))
        assertTrue(out.contains("'yes'"))
        assertTrue(out.contains("1.2300"))
        assertTrue(out.contains("  name:"))
        assertTrue(out.contains("*"))
    }
    @Test fun rejectsMalformedYaml() {
        assertThrows(Exception::class.java) { YamlTextReparser.format("x: [", CancellationToken.NONE) }
    }
}
