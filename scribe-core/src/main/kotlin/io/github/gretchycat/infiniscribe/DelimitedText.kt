package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.CancellationToken

/** Existing viewer CSV/TSV rules, retained for compatibility rather than strict RFC normalization. */
object DelimitedText {
    fun detectDelimiter(sample: String, fileName: String = ""): Char {
        if (fileName.endsWith(".tsv", ignoreCase = true)) return '\t'
        val firstLine = sample.lineSequence().firstOrNull() ?: return ','
        val commaCount = firstLine.count { it == ',' }
        val tabCount = firstLine.count { it == '\t' }
        val semiCount = firstLine.count { it == ';' }
        val pipeCount = firstLine.count { it == '|' }

        return when {
            tabCount > commaCount && tabCount > semiCount -> '\t'
            semiCount > commaCount && semiCount > tabCount -> ';'
            pipeCount > commaCount && pipeCount > semiCount -> '|'
            else -> ','
        }
    }

    fun parseCsv(text: String, delim: Char = ',', cancellation: CancellationToken = CancellationToken.NONE): List<List<String>> {
        cancellation.check()
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false
        var i = 0
        val len = text.length

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
                    currentRow.add(currentField.toString().trim())
                    currentField.clear()
                }
                (c == '\r' || c == '\n') && !insideQuotes -> {
                    if (c == '\r' && i + 1 < len && text[i + 1] == '\n') i++ // Skip CRLF
                    currentRow.add(currentField.toString().trim())
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
            currentRow.add(currentField.toString().trim())
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
