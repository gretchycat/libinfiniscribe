package io.github.gretchycat.infiniscribe

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale

/** Routes for the actual Android SFNT renderer, not every MIME in the font category. */
object FontFormats {
    val extensions = setOf("ttf", "otf", "ttc", "otc")
    val mimeTypes = setOf("font/ttf", "font/otf", "font/collection", "application/font-sfnt")
    fun supports(name: String, mime: String) = mime.substringBefore(';').trim().lowercase(Locale.ROOT) in mimeTypes ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
}

data class FontAxis(val tag: String, val minimum: Float, val default: Float, val maximum: Float)
data class FontFace(val index: Int, val family: String?, val style: String?, val fullName: String?,
    val postScriptName: String?, val glyphCount: Int?, val unitsPerEm: Int?, val weight: Int?,
    val axes: List<FontAxis>)
data class FontDocument(val format: String, val faces: List<FontFace>, val length: Long)

/** Bounded OpenType directory/name inspection. Glyph decoding belongs to the platform backend. */
object FontDocuments {
    const val MAX_BYTES = 64L * 1024 * 1024
    private data class Table(val offset: Long, val length: Long)

    fun inspect(file: File): FontDocument = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        if (length !in 12..MAX_BYTES) throw IOException("Font must be between 12 bytes and 64 MiB")
        fun checkCancelled() { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Font load cancelled") }
        fun range(offset: Long, count: Long) {
            if (offset < 0 || count < 0 || offset > length || count > length - offset)
                throw IOException("Font table is outside the file")
        }
        fun u32() = input.readInt().toLong() and 0xffffffffL
        fun names(table: Table?): Map<Int, String> {
            if (table == null || table.length < 6) return emptyMap()
            input.seek(table.offset)
            val version = input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val strings = input.readUnsignedShort().toLong()
            if (version !in 0..1 || count > 4096 || 6L + count * 12L > table.length ||
                strings < 6L + count * 12L || strings > table.length) throw IOException("Invalid font name table")
            val result = mutableMapOf<Int, Pair<Int, String>>()
            repeat(count) { i ->
                checkCancelled()
                input.seek(table.offset + 6 + i * 12L)
                val platform = input.readUnsignedShort(); val encoding = input.readUnsignedShort()
                val language = input.readUnsignedShort(); val id = input.readUnsignedShort()
                val size = input.readUnsignedShort(); val offset = input.readUnsignedShort()
                if (id !in setOf(1, 2, 4, 6, 16, 17) || size == 0 || size > 8192) return@repeat
                val charset = when {
                    platform == 0 || platform == 3 && encoding in setOf(0, 1, 10) -> Charsets.UTF_16BE
                    platform == 1 && encoding == 0 -> runCatching { Charset.forName("x-MacRoman") }.getOrNull() ?: return@repeat
                    else -> return@repeat
                }
                if (strings + offset + size > table.length) throw IOException("Invalid font name string")
                val score = (if (platform == 3) 30 else if (platform == 0) 20 else 10) +
                    (if (language == 0x0409 || language == 0) 100 else 0)
                if ((result[id]?.first ?: -1) >= score) return@repeat
                input.seek(table.offset + strings + offset)
                val bytes = ByteArray(size); input.readFully(bytes)
                val text = String(bytes, charset).filterNot { it.isISOControl() }.trim().take(256)
                if (text.isNotEmpty()) result[id] = score to text
            }
            return result.mapValues { it.value.second }
        }
        fun face(index: Int, offset: Long): FontFace {
            checkCancelled(); range(offset, 12); input.seek(offset)
            val signature = input.readInt()
            if (signature !in setOf(0x00010000, 0x4f54544f, 0x74727565)) throw IOException("Unsupported font face format")
            val count = input.readUnsignedShort()
            if (count !in 1..4096) throw IOException("Invalid font table count")
            range(offset + 12, count * 16L)
            val tables = mutableMapOf<String, Table>()
            repeat(count) { i ->
                checkCancelled(); input.seek(offset + 12 + i * 16L)
                val tagBytes = ByteArray(4); input.readFully(tagBytes)
                val tag = String(tagBytes, Charsets.US_ASCII)
                input.skipBytes(4)
                val table = Table(u32(), u32()); range(table.offset, table.length)
                if (tables.put(tag, table) != null) throw IOException("Duplicate font table: $tag")
            }
            fun ushort(tag: String, position: Long): Int? = tables[tag]?.takeIf { position + 2 <= it.length }?.let {
                input.seek(it.offset + position); input.readUnsignedShort()
            }
            val n = names(tables["name"])
            val axes = tables["fvar"]?.let { table ->
                if (table.length < 16) throw IOException("Invalid font variation table")
                input.seek(table.offset)
                if (input.readInt() != 0x00010000) throw IOException("Unsupported font variation version")
                val start = input.readUnsignedShort(); input.skipBytes(2)
                val axisCount = input.readUnsignedShort(); val axisSize = input.readUnsignedShort()
                if (axisCount > 64 || axisSize < 20 || start < 16 || start + axisCount * axisSize.toLong() > table.length)
                    throw IOException("Invalid font variation axes")
                List(axisCount) { i ->
                    input.seek(table.offset + start + i * axisSize.toLong())
                    val tag = ByteArray(4); input.readFully(tag)
                    val min = input.readInt() / 65536f; val default = input.readInt() / 65536f; val max = input.readInt() / 65536f
                    if (min > default || default > max) throw IOException("Invalid font variation range")
                    FontAxis(String(tag, Charsets.US_ASCII), min, default, max)
                }
            }.orEmpty()
            return FontFace(index, n[16] ?: n[1], n[17] ?: n[2], n[4], n[6], ushort("maxp", 4),
                ushort("head", 18), ushort("OS/2", 4), axes)
        }
        input.seek(0)
        val signature = input.readInt()
        val offsets = if (signature == 0x74746366) {
            val version = input.readInt(); val count = u32()
            if (version !in setOf(0x00010000, 0x00020000) || count !in 1L..256L)
                throw IOException("Invalid font collection header")
            range(12, count * 4)
            List(count.toInt()) { u32() }
        } else listOf(0L)
        val format = when (signature) {
            0x74746366 -> "OpenType / TrueType collection"
            0x4f54544f -> "OpenType (CFF)"
            0x00010000, 0x74727565 -> "TrueType"
            else -> throw IOException("Unsupported font format. Use TTF, OTF, TTC or OTC.")
        }
        FontDocument(format, offsets.mapIndexed { index, offset -> face(index, offset) }, length)
    }
}
