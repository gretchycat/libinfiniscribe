package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.CancellationToken
import java.io.IOException
import java.io.InputStream

/** Streaming preflight: allow external DOCTYPE identifiers, reject internal subsets before expansion.
 * SAX implementations differ in whether they report internal entity callbacks. This guard runs on
 * the original XML bytes, retaining parser encoding detection and using constant additional memory.
 */
internal class EpubXmlInput(private val input: InputStream, private val maxBytes: Long,
    private val cancellation: CancellationToken) : InputStream() {
    private var bytes = 0L
    private val prefix = IntArray(4)
    private var prefixSize = 0
    private var width = 0
    private var littleEndian = false
    private var unit = 0
    private var unitBytes = 0
    private var mode = "text"
    private var pending = ""
    private var tail = 0
    private var quote = -1

    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        cancellation.check()
        if (len == 0) return 0
        val remaining = maxBytes - bytes
        val budget = if (remaining == Long.MAX_VALUE) remaining else (remaining + 1).coerceAtLeast(1)
        val count = input.read(b, off, minOf(len.toLong(), budget).toInt())
        if (count > 0) bytes += count
        if (bytes > maxBytes) throw IOException("EPUB XML byte budget exceeded")
        for (i in off until off + count.coerceAtLeast(0)) acceptByte(b[i].toInt() and 255)
        return count
    }

    private fun acceptByte(value: Int) {
        if (width == 0) {
            prefix[prefixSize++] = value
            if (prefixSize < 4) return
            val p = prefix
            when {
                (p[0] == 0 && p[1] == 0 && ((p[2] == 254 && p[3] == 255) || (p[2] == 0 && p[3] == 60))) -> width = 4
                (p[0] == 255 && p[1] == 254 && p[2] == 0 && p[3] == 0) || (p[0] == 60 && p[1] == 0 && p[2] == 0 && p[3] == 0) -> { width = 4; littleEndian = true }
                (p[0] == 254 && p[1] == 255) || (p[0] == 0 && p[1] == 60 && p[2] == 0) -> width = 2
                (p[0] == 255 && p[1] == 254) || (p[0] == 60 && p[1] == 0 && p[3] == 0) -> { width = 2; littleEndian = true }
                else -> width = 1 // UTF-8 and ASCII-compatible encodings retain ASCII markup bytes.
            }
            p.forEach(::acceptUnitByte)
        } else acceptUnitByte(value)
    }

    private fun acceptUnitByte(value: Int) {
        unit = if (littleEndian) unit or (value shl (8 * unitBytes)) else (unit shl 8) or value
        if (++unitBytes == width) {
            acceptCharacter(if (unit in 0..127) unit.toChar() else '\uFFFD')
            unit = 0; unitBytes = 0
        }
    }

    private fun acceptCharacter(c: Char) {
        when (mode) {
            "comment", "cdata", "pi" -> {
                tail = ((tail shl 8) or (c.code and 255)) and 0xFFFFFF
                val ended = when (mode) { "comment" -> tail == 0x2D2D3E; "cdata" -> tail == 0x5D5D3E; else -> (tail and 0xFFFF) == 0x3F3E }
                if (ended) { mode = "text"; tail = 0 }
            }
            "dtd" -> when {
                quote >= 0 -> if (c.code == quote) quote = -1
                c == '\'' || c == '"' -> quote = c.code
                c == '[' -> throw IOException("EPUB XML internal DTD subsets are unsupported")
                c == '>' -> mode = "text"
            }
            else -> {
                if (pending.isEmpty() && c != '<') return
                pending += c
                val next = when (pending) { "<!--" -> "comment"; "<![CDATA[" -> "cdata"; "<?" -> "pi"; "<!DOCTYPE" -> "dtd"; else -> null }
                if (next != null) { mode = next; pending = ""; tail = 0 }
                else if (listOf("<!--", "<![CDATA[", "<?", "<!DOCTYPE").none { it.startsWith(pending) }) pending = if (c == '<') "<" else ""
            }
        }
    }
}
