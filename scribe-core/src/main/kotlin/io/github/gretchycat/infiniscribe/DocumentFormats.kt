package io.github.gretchycat.infiniscribe

/** Reader routes only; storage MIME identity remains a separate host concern. */
object DocumentFormats {
    enum class Kind { WORD, SLIDES, SHEET, MOBI, FB2, ODF_TEXT, ODF_SLIDES, ODF_SHEET, RTF }
    val extensions=mapOf(
        "doc" to Kind.WORD,"dot" to Kind.WORD,"docx" to Kind.WORD,"docm" to Kind.WORD,"dotx" to Kind.WORD,"dotm" to Kind.WORD,
        "xls" to Kind.SHEET,"xlt" to Kind.SHEET,"xlsx" to Kind.SHEET,"xlsm" to Kind.SHEET,"xltx" to Kind.SHEET,"xltm" to Kind.SHEET,
        "ppt" to Kind.SLIDES,"pot" to Kind.SLIDES,"pps" to Kind.SLIDES,"pptx" to Kind.SLIDES,"pptm" to Kind.SLIDES,
        "ppsx" to Kind.SLIDES,"ppsm" to Kind.SLIDES,"potx" to Kind.SLIDES,"potm" to Kind.SLIDES,
        "mobi" to Kind.MOBI,"azw" to Kind.MOBI,"azw3" to Kind.MOBI,"prc" to Kind.MOBI,"fb2" to Kind.FB2,
        "odt" to Kind.ODF_TEXT,"ott" to Kind.ODF_TEXT,"fodt" to Kind.ODF_TEXT,
        "ods" to Kind.ODF_SHEET,"ots" to Kind.ODF_SHEET,"fods" to Kind.ODF_SHEET,
        "odp" to Kind.ODF_SLIDES,"otp" to Kind.ODF_SLIDES,"fodp" to Kind.ODF_SLIDES,"rtf" to Kind.RTF)
    val mimeKinds=mapOf(
        "application/msword" to Kind.WORD,"application/vnd.openxmlformats-officedocument.wordprocessingml.document" to Kind.WORD,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to Kind.WORD,
        "application/vnd.ms-word.document.macroenabled.12" to Kind.WORD,"application/vnd.ms-word.template.macroenabled.12" to Kind.WORD,
        "application/vnd.ms-excel" to Kind.SHEET,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to Kind.SHEET,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template" to Kind.SHEET,
        "application/vnd.ms-excel.sheet.macroenabled.12" to Kind.SHEET,"application/vnd.ms-excel.template.macroenabled.12" to Kind.SHEET,
        "application/vnd.ms-powerpoint" to Kind.SLIDES,"application/vnd.openxmlformats-officedocument.presentationml.presentation" to Kind.SLIDES,
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow" to Kind.SLIDES,
        "application/vnd.openxmlformats-officedocument.presentationml.template" to Kind.SLIDES,
        "application/vnd.ms-powerpoint.presentation.macroenabled.12" to Kind.SLIDES,
        "application/vnd.ms-powerpoint.slideshow.macroenabled.12" to Kind.SLIDES,"application/vnd.ms-powerpoint.template.macroenabled.12" to Kind.SLIDES,
        "application/x-mobipocket-ebook" to Kind.MOBI,"application/vnd.amazon.ebook" to Kind.MOBI,"application/x-mobi8-ebook" to Kind.MOBI,"application/vnd.amazon.mobi8-ebook" to Kind.MOBI,
        "application/x-fictionbook+xml" to Kind.FB2,"text/fb2+xml" to Kind.FB2,
        "application/vnd.oasis.opendocument.text" to Kind.ODF_TEXT,"application/vnd.oasis.opendocument.text-template" to Kind.ODF_TEXT,
        "application/vnd.oasis.opendocument.text-flat-xml" to Kind.ODF_TEXT,
        "application/vnd.oasis.opendocument.spreadsheet" to Kind.ODF_SHEET,"application/vnd.oasis.opendocument.spreadsheet-template" to Kind.ODF_SHEET,
        "application/vnd.oasis.opendocument.spreadsheet-flat-xml" to Kind.ODF_SHEET,
        "application/vnd.oasis.opendocument.presentation" to Kind.ODF_SLIDES,"application/vnd.oasis.opendocument.presentation-template" to Kind.ODF_SLIDES,
        "application/vnd.oasis.opendocument.presentation-flat-xml" to Kind.ODF_SLIDES,
        "application/rtf" to Kind.RTF,"text/rtf" to Kind.RTF)
    fun forMime(mime:String?)=mimeKinds[mime.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)]
    fun detect(name:String,mime:String?=null):Kind? {
        forMime(mime)?.let {return it}
        val stated=mime.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        // Explicit text/media/PDF/EPUB identities keep their own reader even if a
        // user has associated them with a traditionally Office filename suffix.
        if(stated.substringBefore('/') in setOf("text","image","audio","video","font") ||
            stated in setOf("application/pdf","application/epub+zip","application/json","application/xml","application/yaml","application/javascript"))return null
        return extensions[name.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)]
    }
    fun canonicalExtension(name:String,mime:String?):String {
        val ext=name.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)
        if(ext in extensions && (forMime(mime)==null || extensions[ext]==forMime(mime)))return ext
        return when(forMime(mime)) {
            Kind.WORD -> if(mime=="application/msword")"doc" else "docx"
            Kind.SHEET -> if(mime=="application/vnd.ms-excel")"xls" else "xlsx"
            Kind.SLIDES -> if(mime=="application/vnd.ms-powerpoint")"ppt" else "pptx"
            Kind.MOBI -> "mobi";Kind.FB2->"fb2";Kind.ODF_TEXT->"odt";Kind.ODF_SHEET->"ods";Kind.ODF_SLIDES->"odp";Kind.RTF->"rtf"
            else -> ext
        }
    }
}
