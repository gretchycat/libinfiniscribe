package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.PositionalReadable
import java.io.EOFException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.math.BigInteger
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.TreeMap

/** Fixed-length byte document. Input is borrowed; edits are sparse and never write the source. */
class HexDocument(private val source: PositionalReadable, val length: Long) {
    init { require(length >= 0) }
    private class Patch { val data = ByteArray(4096); val changed = java.util.BitSet(4096) }
    private val edits = TreeMap<Long, Patch>()
    private var changeCount = 0
    private data class Edit(val offset: Long, val before: ByteArray, val after: ByteArray)
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    val changedCount get() = changeCount
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    /** Borrowed read-only view. Iteration does not duplicate a large byte index. */
    fun changes(): Map<Long, Byte> = object : AbstractMap<Long, Byte>() {
        override val entries: Set<Map.Entry<Long, Byte>> get() = object : AbstractSet<Map.Entry<Long, Byte>>() {
            override val size get() = changeCount
            override fun iterator(): Iterator<Map.Entry<Long, Byte>> = changedEntries().iterator()
        }
    }
    private fun changedEntries(start: Long = 0, end: Long = length): Sequence<Map.Entry<Long, Byte>> = sequence {
        if (start >= end) return@sequence
        for ((block, patch) in edits.subMap(start / 4096, true, (end - 1) / 4096, true)) {
            val base = block * 4096
            var i = patch.changed.nextSetBit((start - base).coerceAtLeast(0).toInt())
            while (i >= 0 && i < 4096 && base + i < end) {
                yield(java.util.AbstractMap.SimpleImmutableEntry(base + i, patch.data[i]))
                i = patch.changed.nextSetBit(i + 1)
            }
        }
    }
    fun changedOffsets(start: Long, end: Long): Set<Long> = changedEntries(start, end).map { it.key }.toSet()
    private fun put(offset: Long, value: Byte?) {
        val block = offset / 4096
        val i = (offset % 4096).toInt()
        val patch = edits[block] ?: if (value == null) return else {
            require(edits.size < 4096) { "Save before editing more than 4096 separate byte blocks" }
            Patch().also { edits[block] = it }
        }
        val wasChanged = patch.changed[i]
        if (value == null) {
            if (wasChanged) { patch.changed.clear(i); changeCount-- }
            if (patch.changed.isEmpty) edits.remove(block)
        } else {
            patch.data[i] = value; patch.changed.set(i)
            if (!wasChanged) changeCount++
        }
    }

    fun read(offset: Long, count: Int, patched: Boolean = true): ByteArray {
        require(offset in 0..length && count >= 0)
        val data = ByteArray(minOf(count.toLong(), length - offset).toInt())
        var total = 0
        while (total < data.size) {
            checkCancelled()
            val n = source.readAt(offset + total, data, total, data.size - total)
            if (n <= 0) throw EOFException("The source ended before offset ${offset + total}")
            total += n
        }
        if (patched && data.isNotEmpty()) {
            val end = offset + data.size
            for ((block, patch) in edits.subMap(offset / 4096, true, (end - 1) / 4096, true)) {
                val base = block * 4096
                var i = patch.changed.nextSetBit((offset - base).coerceAtLeast(0).toInt())
                while (i >= 0 && i < 4096 && base + i < end) {
                    data[(base + i - offset).toInt()] = patch.data[i]
                    i = patch.changed.nextSetBit(i + 1)
                }
            }
        }
        return data
    }

    fun overwrite(offset: Long, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= 4096) { "Enter between 1 and 4096 bytes" }
        change(offset, bytes)
    }
    /** A range fill is one undo unit, even when it spans many read blocks. */
    fun fill(start: Long, end: Long, unit: ByteArray) {
        require(unit.size in 1..4) { "Fill uses 8-, 16-, 24- or 32-bit chunks" }
        require(start >= 0 && end > start && end <= length) { "Choose a nonempty range within this file" }
        require((end - start) % unit.size == 0L) { "The range must contain a whole number of chunks" }
        require(end - start <= 1024 * 1024) { "Fill up to 1 MiB per operation" }
        change(start, ByteArray((end - start).toInt()) { unit[it % unit.size] })
    }
    private fun change(offset: Long, bytes: ByteArray) {
        require(offset >= 0 && offset <= length && bytes.size.toLong() <= length - offset) { "Replacement extends beyond the file" }
        val before = read(offset, bytes.size)
        if (before.contentEquals(bytes)) return
        // Bound the sparse index as well as undo history, independently of file size.
        val replaced = changedEntries(offset, offset + bytes.size).count()
        require(changeCount - replaced + bytes.size <= 1024 * 1024) { "Save or undo edits before changing more than 1 MiB of bytes" }
        val firstBlock = offset / 4096
        val lastBlock = (offset + bytes.size - 1) / 4096
        require(edits.size + lastBlock - firstBlock + 1 - edits.subMap(firstBlock, true, lastBlock, true).size <= 4096) {
            "Save before editing more than 4096 separate byte blocks"
        }
        apply(offset, bytes)
        undo.addLast(Edit(offset, before, bytes.copyOf()))
        while (undo.size > 100 || undo.sumOf { it.before.size.toLong() + it.after.size } > 4L * 1024 * 1024) undo.removeFirst()
        redo.clear()
    }
    private fun apply(offset: Long, data: ByteArray) {
        val original = read(offset, data.size, patched = false)
        data.forEachIndexed { i, byte ->
            put(offset + i, byte.takeUnless { it == original[i] })
        }
    }
    fun undo() { undo.removeLastOrNull()?.let { apply(it.offset, it.before); redo.addLast(it) } }
    fun redo() { redo.removeLastOrNull()?.let { apply(it.offset, it.after); undo.addLast(it) } }
    fun restore(changes: Map<Long, Byte>) {
        require(changes.size <= 1024 * 1024 && changes.keys.all { it >= 0 && it < length }) { "Invalid edit draft" }
        restoreEntries(changes.entries.asSequence().map { it.key to it.value })
    }
    fun restoreEntries(changes: Sequence<Pair<Long, Byte>>) {
        edits.clear(); changeCount = 0; undo.clear(); redo.clear()
        var count = 0
        for ((offset, byte) in changes) {
            require(offset >= 0 && offset < length && ++count <= 1024 * 1024) { "Invalid edit draft" }
            put(offset, byte)
        }
    }

    /** KMP crosses read boundaries without keeping the whole file or result list in memory. */
    fun find(pattern: ByteArray, start: Long, end: Long = length, cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {}): Long? {
        require(pattern.isNotEmpty() && pattern.size <= 4096) { "Search must contain 1–4096 bytes" }
        require(start >= 0 && end in start..length)
        val prefix = IntArray(pattern.size)
        var matched = 0
        for (i in 1 until pattern.size) {
            while (matched > 0 && pattern[i] != pattern[matched]) matched = prefix[matched - 1]
            if (pattern[i] == pattern[matched]) matched++
            prefix[i] = matched
        }
        matched = 0
        var at = start
        while (at < end) {
            checkCancelled(cancelled)
            val data = read(at, minOf(65536L, end - at).toInt())
            for (i in data.indices) {
                if (i and 4095 == 0) checkCancelled(cancelled)
                while (matched > 0 && data[i] != pattern[matched]) matched = prefix[matched - 1]
                if (data[i] == pattern[matched]) matched++
                if (matched == pattern.size) return at + i - pattern.size + 1
            }
            at += data.size; progress(at)
        }
        return null
    }
    fun writeTo(output: OutputStream, patched: Boolean = true, cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {}) {
        var at = 0L
        while (at < length) {
            checkCancelled(cancelled)
            val data = read(at, minOf(65536L, length - at).toInt(), patched)
            output.write(data); at += data.size; progress(at)
        }
    }
    data class Checksums(val sum8: Int, val crc32: Long, val sha256: String)
    fun checksums(start: Long = 0, end: Long = length, cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {}): Checksums {
        require(start >= 0 && end in start..length)
        val crc = java.util.zip.CRC32()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        var sum = 0
        var at = start
        while (at < end) {
            checkCancelled(cancelled)
            val bytes = read(at, minOf(65536L, end - at).toInt())
            bytes.forEach { sum = (sum + (it.toInt() and 255)) and 255 }
            crc.update(bytes); hash.update(bytes)
            at += bytes.size; progress(at)
        }
        return Checksums(sum, crc.value, hash.digest().joinToString("") { "%02x".format(it) })
    }
    companion object {
        private fun checkCancelled(cancelled: () -> Boolean = { false }) {
            if (Thread.currentThread().isInterrupted || cancelled()) throw InterruptedIOException("Cancelled")
        }
    }
}

object HexEncoding {
    enum class Text(val title: String) { UTF8("UTF-8"), CP437("CP437") }
    data class Glyph(val offset: Long, val byteCount: Int, val text: String)
    fun hex(input: String): ByteArray {
        val compact = input.replace(Regex("(?i)0x"), "").replace(Regex("[\\s,_:-]"), "")
        require(compact.isNotEmpty() && compact.length % 2 == 0 && compact.all { it.digitToIntOrNull(16) != null }) {
            "Enter complete hexadecimal bytes, for example DE AD BE EF"
        }
        require(compact.length <= 8192) { "At most 4096 bytes per search or edit" }
        return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun binary(input: String): ByteArray {
        val bits = input.replace(Regex("[\\s_]"), "")
        require(bits.isNotEmpty() && bits.length % 8 == 0 && bits.all { it == '0' || it == '1' }) {
            "Enter groups of 8 bits, for example 01001000 01101001"
        }
        require(bits.length <= 32768) { "At most 4096 bytes per search or edit" }
        return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
    }
    fun text(input: String, encoding: Text): ByteArray {
        require(input.isNotEmpty() && input.length <= 4096) { "Enter between 1 and 4096 encoded bytes" }
        val charset = if (encoding == Text.UTF8) Charsets.UTF_8 else DosTextEncoding.cp437
        val buffer = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(input))
        require(buffer.remaining() in 1..4096) { "Enter between 1 and 4096 encoded bytes" }
        return ByteArray(buffer.remaining()).also(buffer::get)
    }
    fun offset(input: String): Long {
        val text = input.trim().replace("_", "")
        val value = if (text.startsWith("0x", true)) text.drop(2).toLongOrNull(16) else text.toLongOrNull()
        require(value != null && value >= 0) { "Enter a decimal offset or hexadecimal offset beginning with 0x" }
        return value
    }
    fun integer(input: String, bits: Int, signed: Boolean, littleEndian: Boolean): ByteArray {
        require(bits in listOf(8, 16, 24, 32, 64))
        var raw = input.trim().replace("_", "")
        val negative = raw.startsWith('-')
        raw = raw.removePrefix("-").removePrefix("+")
        val number = (if (raw.startsWith("0x", true)) BigInteger(raw.drop(2), 16) else BigInteger(raw, 10))
            .let { if (negative) it.negate() else it }
        val limit = BigInteger.ONE.shiftLeft(if (signed) bits - 1 else bits)
        require(number >= (if (signed) limit.negate() else BigInteger.ZERO) && number < limit) { "Integer does not fit the selected width and signedness" }
        val value = if (number.signum() < 0) number + BigInteger.ONE.shiftLeft(bits) else number
        val big = ByteArray(bits / 8) { i -> value.shiftRight(bits - 8 - i * 8).toByte() }
        return if (littleEndian) big.reversedArray() else big
    }
    fun printable(codePoint: Int): Boolean = Character.isValidCodePoint(codePoint) &&
        !Character.isISOControl(codePoint) && Character.getType(codePoint) !in listOf(
            Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(), Character.SURROGATE.toInt(),
            Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt()) && codePoint != 0xFFFD

    /** UTF-8 glyphs carry byte spans. Continuation bytes stay empty, preserving offset alignment. */
    fun glyphs(bytes: ByteArray, offset: Long, encoding: Text): List<Glyph> {
        val result = ArrayList<Glyph>()
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 255
            var count = 1
            var cp = b
            if (encoding == Text.CP437) cp = String(byteArrayOf(bytes[i]), DosTextEncoding.cp437)[0].code
            else if (b >= 128) {
                count = when (b) { in 0xC2..0xDF -> 2; in 0xE0..0xEF -> 3; in 0xF0..0xF4 -> 4; else -> 1 }
                cp = if (count == 1 || i + count > bytes.size) 0xFFFD else b and (0x7f shr count)
                if (cp != 0xFFFD) {
                    for (n in 1 until count) {
                        val next = bytes[i + n].toInt() and 255
                        if (next !in 0x80..0xBF) { cp = 0xFFFD; break }
                        cp = (cp shl 6) or (next and 63)
                    }
                    if (cp < (when (count) { 2 -> 128; 3 -> 2048; else -> 65536 }) || cp > 0x10FFFF || cp in 0xD800..0xDFFF) cp = 0xFFFD
                }
                if (cp == 0xFFFD) count = 1
            }
            result.add(Glyph(offset + i, count, if (printable(cp)) String(Character.toChars(cp)) else ""))
            i += count
        }
        return result
    }
}
