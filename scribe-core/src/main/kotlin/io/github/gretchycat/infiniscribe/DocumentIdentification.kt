package io.github.gretchycat.infiniscribe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Bounded structural checks shared by Scribe format handlers during file identity discovery. */
object DocumentIdentification {
    private const val ZIP_ENTRY_LIMIT = 64 * 1024

    fun epub(bytes: ByteArray): Boolean = storedMimeType(bytes) == "application/epub+zip"

    fun openDocumentMime(bytes: ByteArray): String? = storedMimeType(bytes)?.takeIf {
        it in setOf("application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template",
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet-template",
            "application/vnd.oasis.opendocument.presentation", "application/vnd.oasis.opendocument.presentation-template")
    }

    fun openXmlMime(bytes: ByteArray): String? {
        val contents = zipEntryText(bytes, "[Content_Types].xml") ?: return null
        val lower = contents.lowercase(Locale.ROOT)
        val word = lower.contains("wordprocessingml.document.main+xml") || lower.contains("wordprocessingml.template.main+xml") ||
            lower.contains("ms-word.document.macroenabled.main+xml") || lower.contains("ms-word.template.macroenabledtemplate.main+xml")
        val sheet = lower.contains("spreadsheetml.sheet.main+xml") || lower.contains("spreadsheetml.template.main+xml") ||
            lower.contains("ms-excel.sheet.macroenabled.main+xml") || lower.contains("ms-excel.template.macroenabled.main+xml")
        val slides = lower.contains("presentationml.presentation.main+xml") || lower.contains("presentationml.slideshow.main+xml") ||
            lower.contains("presentationml.template.main+xml") || lower.contains("ms-powerpoint.presentation.macroenabled.main+xml") ||
            lower.contains("ms-powerpoint.slideshow.macroenabled.main+xml") || lower.contains("ms-powerpoint.template.macroenabled.main+xml")
        return when {
            word -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            sheet -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            slides -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> null
        }
    }

    fun flatOpenDocumentMime(bytes: ByteArray): String? {
        val text = String(bytes, Charsets.UTF_8).take(64 * 1024).lowercase(Locale.ROOT)
        if (!text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("<?xml")) return null
        if (!text.contains("urn:oasis:names:tc:opendocument:xmlns:office:1.0")) return null
        return when {
            text.contains("<office:document-content") && text.contains("<office:spreadsheet") ->
                "application/vnd.oasis.opendocument.spreadsheet-flat-xml"
            text.contains("<office:document-content") && text.contains("<office:presentation") ->
                "application/vnd.oasis.opendocument.presentation-flat-xml"
            text.contains("<office:document-content") || text.contains("<office:document ") ->
                "application/vnd.oasis.opendocument.text-flat-xml"
            else -> null
        }
    }

    fun fictionBook(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.UTF_8).take(64 * 1024).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return Regex("(?is)^(?:<\\?xml[^>]*>\\s*)?(?:<!--.*?-->\\s*)*<(?:[a-z0-9_]+:)?FictionBook(?:\\s|>)").containsMatchIn(text)
    }

    fun richText(bytes: ByteArray): Boolean = bytes.size >= 6 &&
        String(bytes, 0, 5, Charsets.US_ASCII) == "{\\rtf" && bytes[5].toInt().toChar().let { it.isDigit() || it == ' ' || it == '\\' }

    fun mobipocket(bytes: ByteArray, length: Long?): Boolean {
        if (bytes.size < 86 || String(bytes, 60, 8, Charsets.US_ASCII) != "BOOKMOBI") return false
        val records = be16(bytes, 76)
        val firstRecord = be32(bytes, 78)
        return records in 1..32768 && firstRecord >= 86 &&
            (length == null || firstRecord < length) && firstRecord <= bytes.size
    }

    /** Validate the compound-file header before preserving a legacy Office extension association. */
    fun compoundOffice(bytes: ByteArray): Boolean {
        if (bytes.size < 512 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(
                0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()))) return false
        val major = le16(bytes, 26)
        val sectorShift = le16(bytes, 30)
        val miniShift = le16(bytes, 32)
        val fatSectors = le32(bytes, 44)
        val firstDirectorySector = le32(bytes, 48)
        return (major == 3 && sectorShift == 9 || major == 4 && sectorShift == 12) && miniShift == 6 &&
            fatSectors in 1L..4096L && firstDirectorySector != 0xffffffffL
    }

    private fun storedMimeType(bytes: ByteArray): String? = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry ?: return null
            if (entry.name != "mimetype" || entry.method != java.util.zip.ZipEntry.STORED) return null
            readEntry(zip, 256)?.toString(Charsets.US_ASCII)?.trim()
        }
    } catch (_: Exception) { null }

    private fun zipEntryText(bytes: ByteArray, name: String): String? = try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var skipped = 0
            while (entry != null && skipped < 8) {
                if (entry.name == name) return readEntry(zip, ZIP_ENTRY_LIMIT)?.toString(Charsets.UTF_8)
                // Avoid inflating arbitrary package entries while walking to the manifest.
                val ignored = readEntry(zip, 8192) ?: return null
                if (ignored.size == 8192) return null
                skipped++
                entry = zip.nextEntry
            }
            null
        }
    } catch (_: Exception) { null }

    private fun readEntry(zip: ZipInputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() <= limit) {
            val count = zip.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) return output.toByteArray()
            if (count == 0) return null
            output.write(buffer, 0, count)
        }
        return null
    }

    private fun be16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    private fun le16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    private fun be32(bytes: ByteArray, at: Int) = (be16(bytes, at).toLong() shl 16) or be16(bytes, at + 2).toLong()
    private fun le32(bytes: ByteArray, at: Int) = le16(bytes, at).toLong() or (le16(bytes, at + 2).toLong() shl 16)
}
