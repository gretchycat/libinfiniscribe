package io.github.gretchycat.infiniscribe.reparse

import android.util.JsonReader
import android.util.JsonToken
import io.github.gretchycat.infinistream.CancellationToken

/** Validates JSON, then changes only whitespace outside strings, preserving keys and number spelling. */
object JsonTextReparser : TextReparser {
    override val name = "JSON"
    override val extensions = setOf("json", "geojson", "ipynb")
    override val mimeTypes = setOf("application/json", "text/json", "application/x-json")
    override val mimeSuffixes = setOf("+json")
    override val leadingCharacters = setOf('{', '[')
    override fun format(text: String, cancellation: CancellationToken): String {
        val source = text.removePrefix("\uFEFF")
        // A wrapper permits scalar JSON roots while retaining JsonReader's strict grammar.
        // Its iterative skip avoids building a second object tree for large documents.
        JsonReader(ReparseReader("[$source]", cancellation)).use { reader ->
            reader.beginArray()
            reader.skipValue()
            require(!reader.hasNext()) { "Trailing JSON content" }
            reader.endArray()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" }
        }
        val out = ReparseWriter(cancellation)
        var depth = 0
        var quoted = false
        var escaped = false
        var previous = ' '
        fun newline() {
            out.append('\n')
            repeat(minOf(depth, 64)) { out.append("  ") }
        }
        for ((index, char) in source.withIndex()) {
            if (index % 4096 == 0) {
                cancellation.check()
            }
            if (quoted) {
                out.append(char)
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
                continue
            }
            if (char.isWhitespace()) continue
            // Delay indentation after an opening bracket so empty containers stay compact.
            if ((previous == '{' || previous == '[') && char != '}' && char != ']') newline()
            when (char) {
                '"' -> { quoted = true; out.append(char) }
                '{', '[' -> { out.append(char); depth++ }
                '}', ']' -> {
                    depth--
                    if (previous != '{' && previous != '[') newline()
                    out.append(char)
                }
                ',' -> { out.append(char); newline() }
                ':' -> out.append(": ")
                else -> out.append(char)
            }
            previous = char
        }
        cancellation.check()
        return out.toString()
    }
}
