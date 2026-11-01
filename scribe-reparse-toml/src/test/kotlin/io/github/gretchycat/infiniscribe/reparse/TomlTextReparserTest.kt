package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.CancellationToken
import org.tomlj.Toml
import org.junit.Assert.*
import org.junit.Test

class TomlTextReparserTest {
    @Test fun retainsTablesArraysInlineTablesAndTimestamps() {
        val input = "title='example'\n[server]\nport=8080\ndate=1979-05-27T07:32:00Z\nitems=[1,2,3]\ninline={a=1,b='two'}\n"
        val out = TomlTextReparser.format(input, CancellationToken.NONE)
        val before = Toml.parse(input)
        val after = Toml.parse(out)
        assertFalse(after.hasErrors())
        assertEquals(before.toJson(), after.toJson())
        assertEquals(8080L, after.getLong("server.port"))
    }
    @Test fun rejectsMalformedToml() {
        assertThrows(IllegalArgumentException::class.java) { TomlTextReparser.format("x=[", CancellationToken.NONE) }
    }
}
