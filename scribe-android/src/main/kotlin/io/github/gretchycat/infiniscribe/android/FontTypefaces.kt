package io.github.gretchycat.infiniscribe.android

import android.graphics.Typeface
import io.github.gretchycat.infiniscribe.FontDocument
import java.io.File
import java.io.IOException

/** Native glyph rendering, with no synthetic fallback passed off as a successful font load. */
object FontTypefaces {
    fun open(file: File, document: FontDocument, index: Int): Typeface {
        require(index in document.faces.indices) { "Font face index is out of range" }
        return Typeface.Builder(file).setTtcIndex(index).build()
            ?: throw IOException("Android cannot render this font face")
    }
}
