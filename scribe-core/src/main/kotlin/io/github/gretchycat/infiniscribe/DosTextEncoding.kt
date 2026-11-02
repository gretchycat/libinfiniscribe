package io.github.gretchycat.infiniscribe

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Content evidence for DOS text; never treat a file extension as proof of an encoding. */
object DosTextEncoding {
    val cp437: Charset = Charset.forName("IBM437")
    data class Detection(val charset: Charset, val dosSurface: Boolean, val ansi: Boolean, val evidence: String)
    private val csi = Regex("\u001b\\[[0-?]*[ -/]*[@-~]")
    fun detect(bytes: ByteArray, name: String = "", complete: Boolean = true): Detection {
        fun byte(i: Int) = bytes[i].toInt() and 255
        fun unicode(charset: String) = Detection(Charset.forName(charset),false,false,"Unicode BOM")
        if (bytes.size >= 4) {
            if (byte(0)==0xff && byte(1)==0xfe && byte(2)==0 && byte(3)==0) return unicode("UTF-32LE")
            if (byte(0)==0 && byte(1)==0 && byte(2)==0xfe && byte(3)==0xff) return unicode("UTF-32BE")
        }
        if (bytes.size >= 3 && byte(0)==0xef && byte(1)==0xbb && byte(2)==0xbf) return unicode("UTF-8")
        if (bytes.size >= 2) {
            if (byte(0)==0xff && byte(1)==0xfe) return unicode("UTF-16LE")
            if (byte(0)==0xfe && byte(1)==0xff) return unicode("UTF-16BE")
        }
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        // A prefix may end in the middle of a valid UTF-8 character.
        val validUtf8 = !utf8.decode(ByteBuffer.wrap(bytes),CharBuffer.allocate(bytes.size+1),complete).isError
        val high = bytes.count { it.toInt() and 128 != 0 }
        if (validUtf8 && high > 0) return Detection(Charsets.UTF_8,false,false,"Valid UTF-8")
        val ascii = String(bytes,Charsets.ISO_8859_1)
        val escapes = csi.findAll(ascii).take(4096).count()
        val music = Regex("\u001b\\[(?:M|N|\\|)[a-gA-GlLmMnNoOpPsStT0-9.\\-+#<> ]+\u000e").containsMatchIn(ascii)
        val ansi = music || escapes >= 2
        val sauce = bytes.size >= 128 && ascii.regionMatches(bytes.size-128,"SAUCE00",0,7)
        var run = 0; var longest = 0; var boxes = 0
        for (value in bytes) {
            if (value.toInt() and 255 in 0xb0..0xdf) { boxes++; run++; longest=maxOf(longest,run) } else run=0
        }
        val binary = bytes.any { it == 0.toByte() } || bytes.count {
            val b=it.toInt() and 255
            b < 32 && b !in setOf(7,8,9,10,12,13,14,15,26,27)
        } * 20 > bytes.size.coerceAtLeast(1)
        val drawing = longest >= 3 || (boxes >= 6 && boxes*12 >= bytes.size)
        val oemLetters=bytes.indices.count { i ->
            val b=byte(i)
            b in 0x80..0x90 && b!=0x85 &&
                ((i>0 && byte(i-1) in 65..122) || (i<bytes.lastIndex && byte(i+1) in 65..122))
        }
        val ext = name.substringAfterLast('.',"").lowercase(Locale.ROOT)
        val hint = ext in setOf("ans","ansi","asc","diz","nfo")
        val leaf=name.substringAfterLast('/').substringAfterLast('\\')
        val shortName=Regex("[A-Za-z0-9_~!#%&()@^`{}-]{1,8}\\.[A-Za-z0-9_~!#%&()@^`{}-]{1,3}").matches(leaf)
        val legacyNameEvidence=shortName && !validUtf8 && (ascii.contains("\r\n") || bytes.lastOrNull()==26.toByte())
        val dos = !binary && (ansi || sauce || (!validUtf8 && (drawing || oemLetters>=2)) || hint || legacyNameEvidence)
        return if (dos) Detection(cp437,true,ansi,when {
            ansi -> "Repeated ANSI control sequences"
            sauce -> "SAUCE metadata"
            drawing -> "CP437 box drawing"
            oemLetters>=2 -> "CP437 OEM letters in text"
            hint -> "DOS text hint with ASCII or non-UTF-8 bytes"
            else -> "8.3 name with DOS byte/line-ending clues"
        }) else Detection(Charsets.UTF_8,false,false,if(validUtf8) "Valid UTF-8/ASCII" else "Encoding uncertain")
    }
}
