package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.CancellationToken
import org.tomlj.Toml

/** TOML's typed parser preserves tables, arrays and date/time values in the reconstructed view. */
object TomlTextReparser : TextReparser {
    override val name = "TOML"
    override val extensions = setOf("toml")
    override val mimeTypes = setOf("application/toml", "text/toml", "application/x-toml")
    override fun format(text: String, cancellation: CancellationToken): String {
        val parsed = ReparseReader(text, cancellation).use { Toml.parse(it) }
        require(!parsed.hasErrors()) { parsed.errors().take(3).joinToString("; ") }
        val out = ReparseWriter(cancellation)
        parsed.toToml(out)
        cancellation.check()
        return out.toString()
    }
}
