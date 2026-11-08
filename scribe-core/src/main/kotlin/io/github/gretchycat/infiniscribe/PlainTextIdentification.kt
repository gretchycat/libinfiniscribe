package io.github.gretchycat.infiniscribe

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/** Bounded content sniffing for plain text whose name and declared MIME are inconclusive. */
object PlainTextIdentification {
    data class Result(val charset: Charset, val evidence: String)

    private val allowedControls = setOf('\t', '\n', '\r', '\u000c', '\u000e', '\u000f', '\u001b', '\u0007', '\b')
    private val binarySignatures = listOf(
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), // PNG
        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), // JPEG
        "GIF8".toByteArray(), "BM".toByteArray(), "RIFF".toByteArray(), "OggS".toByteArray(),
        "ID3".toByteArray(), "PK\u0003\u0004".toByteArray(), "PK\u0005\u0006".toByteArray(),
        "%PDF-".toByteArray(), "7z\u00bc\u00af\u001c".toByteArray(), "Rar!".toByteArray(),
        "SQLite format 3\u0000".toByteArray(), "fLaC".toByteArray(), "MThd".toByteArray(),
        byteArrayOf(0x7f, 0x45, 0x4c, 0x46), // ELF
        byteArrayOf(0x4d, 0x5a) // PE/DOS executable
    )

    /** Returns null for binary-looking data. It reads only the caller's bounded prefix. */
    fun detect(prefix: ByteArray): Result? {
        if (binarySignatures.any { signature -> prefix.size >= signature.size &&
                signature.indices.all { prefix[it] == signature[it] } }) return null
        if (prefix.any { it == 0.toByte() }) {
            // UTF-16/32 is accepted only when its byte order is declared by a BOM.
            val charset = when {
                prefix.startsWith(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00, 0x00)) -> charsetOrNull("UTF-32LE")
                prefix.startsWith(byteArrayOf(0x00, 0x00, 0xfe.toByte(), 0xff.toByte())) -> charsetOrNull("UTF-32BE")
                prefix.startsWith(byteArrayOf(0xff.toByte(), 0xfe.toByte())) -> Charsets.UTF_16LE
                prefix.startsWith(byteArrayOf(0xfe.toByte(), 0xff.toByte())) -> Charsets.UTF_16BE
                else -> null
            } ?: return null
            return if (isReadable(prefix, charset, skipBom = true)) Result(charset, "Unicode BOM") else null
        }

        val utf8Bom = prefix.startsWith(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        if ((utf8Bom || validUtf8(prefix)) && isReadable(prefix, Charsets.UTF_8, skipBom = utf8Bom)) {
            return Result(Charsets.UTF_8, if (utf8Bom) "UTF-8 BOM" else "valid UTF-8/ASCII text")
        }
        val cp437 = DosTextEncoding.cp437
        return if (isReadable(prefix, cp437)) Result(cp437, "printable CP437 text") else null
    }

    private fun validUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: Exception) { false }

    private fun isReadable(bytes: ByteArray, charset: Charset, skipBom: Boolean = false): Boolean = try {
        val start = if (!skipBom) 0 else when (charset.name().uppercase()) {
            "UTF-8" -> 3
            "UTF-16LE", "UTF-16BE" -> 2
            "UTF-32LE", "UTF-32BE" -> 4
            else -> 0
        }
        val decoded = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start.coerceAtMost(bytes.size), bytes.size - start.coerceAtMost(bytes.size)))
        decoded.toString().codePoints().allMatch { cp ->
            val char = cp.toChar()
            !Character.isISOControl(cp) || char in allowedControls || cp == 0x1a
        }
    } catch (_: Exception) { false }

    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        prefix.indices.all { this[it] == prefix[it] }
}
