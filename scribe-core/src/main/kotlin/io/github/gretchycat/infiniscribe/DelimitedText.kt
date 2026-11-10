package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.CancellationToken

/** Existing viewer CSV/TSV rules, retained for compatibility rather than strict RFC normalization. */
object DelimitedText {
    fun detectDelimiter(sample: String, fileName: String = ""): Char {
        if (fileName.endsWith(".tsv", ignoreCase = true)) return '\t'
        val counts = mapOf(',' to 0, '\t' to 0, ';' to 0, '|' to 0).toMutableMap()
        var quoted = false
        var index = if (sample.startsWith('\uFEFF')) 1 else 0
        while (index < sample.length) {
            val char = sample[index]
            if (char == '"') {
                if (quoted && index + 1 < sample.length && sample[index + 1] == '"') index++
                else quoted = !quoted
            } else if ((char == '\r' || char == '\n') && !quoted) {
                break
            } else if (!quoted && char in counts) {
                counts[char] = counts.getValue(char) + 1
            }
            index++
        }
        val commas = counts.getValue(',')
        val tabs = counts.getValue('\t')
        val semicolons = counts.getValue(';')
        val pipes = counts.getValue('|')
        return when {
            tabs > commas && tabs > semicolons -> '\t'
            semicolons > commas && semicolons > tabs -> ';'
            pipes > commas && pipes > semicolons -> '|'
            else -> ','
        }
    }

    fun parseCsv(text: String, delim: Char = ',', cancellation: CancellationToken = CancellationToken.NONE,
        preserveWhitespace: Boolean = false): List<List<String>> {
        cancellation.check()
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        val len = text.length
        fun fieldValue(): String = currentField.toString().let { if (preserveWhitespace) it else it.trim() }

        while (i < len) {
            if ((i and 4095) <= 1) cancellation.check()
            val c = text[i]
            when {
                c == '"' -> {
                    if (insideQuotes && i + 1 < len && text[i + 1] == '"') {
                        currentField.append('"')
                        i++ // Skip escaped quote
                    } else {
                        insideQuotes = !insideQuotes
                    }
                }
                c == delim && !insideQuotes -> {
                    currentRow.add(fieldValue())
                    currentField.clear()
                }
                (c == '\r' || c == '\n') && !insideQuotes -> {
                    if (c == '\r' && i + 1 < len && text[i + 1] == '\n') i++ // Skip CRLF
                    currentRow.add(fieldValue())
                    currentField.clear()
                    if (currentRow.isNotEmpty() && currentRow.any { it.isNotEmpty() }) {
                        rows.add(currentRow.toList())
                    }
                    currentRow.clear()
                }
                else -> {
                    currentField.append(c)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(fieldValue())
            rows.add(currentRow.toList())
        }
        return rows
    }

    fun formatCsv(rows: List<List<String>>, delimiter: Char = ',', cancellation: CancellationToken = CancellationToken.NONE): String {
        cancellation.check()
        return buildString {
            rows.forEach { row ->
                cancellation.check()
                val line = row.joinToString(delimiter.toString()) { field ->
                    if (field.contains(delimiter) || field.contains('"') || field.contains('\n') || field.contains('\r')) {
                        "\"" + field.replace("\"", "\"\"") + "\""
                    } else {
                        field
                    }
                }
                append(line).append("\n")
            }
        }
    }
}
