package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.CancellationToken
import java.io.Reader
import java.io.StringReader
import java.io.Writer

/** Produces a display representation; implementations never mutate or save source content. */
interface TextReparser {
    val name: String
    val extensions: Set<String> get() = emptySet()
    val mimeTypes: Set<String> get() = emptySet()
    val mimeSuffixes: Set<String> get() = emptySet()
    val leadingCharacters: Set<Char> get() = emptySet()
    fun format(text: String, cancellation: CancellationToken): String
}

/** Bounds expansion and makes serializers cooperatively cancellable. */
class ReparseWriter(private val cancellation: CancellationToken) : Writer() {
    private val output = StringBuilder()
    override fun write(chars: CharArray, offset: Int, length: Int) {
        cancellation.check()
        require(output.length.toLong() + length <= MAX_CHARACTERS) { "Reparsed text exceeds 16777216 characters" }
        output.append(chars, offset, length)
    }
    override fun write(text: String, offset: Int, length: Int) {
        cancellation.check()
        require(output.length.toLong() + length <= MAX_CHARACTERS) { "Reparsed text exceeds 16777216 characters" }
        output.append(text, offset, offset + length)
    }
    override fun write(char: Int) {
        cancellation.check()
        require(output.length < MAX_CHARACTERS) { "Reparsed text exceeds 16777216 characters" }
        output.append(char.toChar())
    }
    override fun flush() {}
    override fun close() {}
    override fun toString() = output.toString()
    companion object { private const val MAX_CHARACTERS = 16 * 1024 * 1024 }
}

class ReparseReader(text: String, private val cancellation: CancellationToken) : Reader() {
    private val input = StringReader(text)
    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        cancellation.check()
        return input.read(buffer, offset, minOf(length, 4096))
    }
    override fun close() = input.close()
}

/** Selection uses explicit file metadata first, then bounded content hints from installed backends. */
class TextReparserRegistry(private val reparsers: List<TextReparser>? = null) {
    fun detect(fileName: String, mime: String, text: String): TextReparser? {
        val reparsers = reparsers ?: ReparserCapabilities.parsers()
        val extension = fileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val type = mime.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        reparsers.firstOrNull { extension in it.extensions }?.let { return it }
        reparsers.firstOrNull { type in it.mimeTypes || it.mimeSuffixes.any(type::endsWith) }?.let { return it }
        val first = text.firstOrNull { !it.isWhitespace() && it != '\uFEFF' } ?: return null
        return reparsers.firstOrNull { first in it.leadingCharacters }
    }
}
