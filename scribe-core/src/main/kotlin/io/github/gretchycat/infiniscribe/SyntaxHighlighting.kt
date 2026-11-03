package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.*

/** UTF-16 offsets match decoded text, never original encoded byte offsets. */
data class ScopedTextRange(val start: Int, val end: Int, val scopes: List<String>)
data class SyntaxState(val terminator: String? = null, val scope: String? = null, val escaped: Boolean = false,
    val inTag: Boolean = false)
data class SyntaxHighlight(val ranges: List<ScopedTextRange>, val endState: SyntaxState, val truncated: Boolean,
    val contextComplete: Boolean)

/** Bounded lexical highlighting with TextMate scope names. This is not a TextMate/Oniguruma grammar interpreter. */
object SyntaxHighlighting {
    val descriptor = ProcessorDescriptor("scribe.text.syntax",
        listOf(PortDescriptor("text", setOf(DataKind.TEXT), attributes = mapOf("storage" to "text-document"))),
        listOf(PortDescriptor("styled", setOf(DataKind.TEXT), attributes = mapOf("storage" to "scoped-text", "offsetUnits" to "utf16"))))
    val languages = listOf("plain", "json", "javascript", "typescript", "java", "kotlin", "c", "cpp", "csharp", "python",
        "shell", "sql", "xml", "html", "css", "yaml", "toml", "ini", "markdown")
    fun detect(fileName: String, mime: String = "", firstLine: String = ""): String = when (fileName.substringAfterLast('.').lowercase()) {
        "json", "jsonc" -> "json"; "js", "mjs", "cjs", "jsx" -> "javascript"; "ts", "tsx", "mts", "cts" -> "typescript"
        "java" -> "java"; "kt", "kts" -> "kotlin"; "c", "h" -> "c"; "cpp", "cc", "cxx", "hpp", "hh", "hp", "hxx", "ipp", "inl", "tcc" -> "cpp"; "cs" -> "csharp"
        "py", "pyw", "pyi", "py2", "py3" -> "python"; "sh", "bash", "zsh", "ksh" -> "shell"; "sql" -> "sql"; "xml", "svg", "xsd", "xsl", "xslt", "plist" -> "xml"
        "html", "htm", "xhtml" -> "html"; "css", "scss", "sass", "less" -> "css"; "yaml", "yml" -> "yaml"
        "toml" -> "toml"; "ini", "cfg", "conf", "properties" -> "ini"; "md", "markdown" -> "markdown"
        else -> when {
            mime.contains("json") -> "json"; mime.contains("xml") -> "xml"; mime == "text/html" -> "html"
            mime in setOf("text/x-csrc", "text/x-chdr", "text/x-c") -> "c"
            mime in setOf("text/x-c++src", "text/x-c++hdr", "text/x-c++") -> "cpp"
            mime == "text/x-csharp" -> "csharp"; mime == "text/x-java" -> "java"; mime == "text/x-kotlin" -> "kotlin"
            mime in setOf("application/javascript", "text/javascript", "application/x-javascript") -> "javascript"
            mime in setOf("text/typescript", "application/typescript") -> "typescript"
            mime in setOf("text/x-python", "text/x-python2", "text/x-python3", "application/x-python") -> "python"
            mime in setOf("application/x-sh", "application/x-shellscript", "text/x-shellscript", "text/x-sh") -> "shell"
            mime == "text/css" || mime in setOf("text/x-scss", "text/x-sass", "text/x-less") -> "css"
            mime in setOf("application/sql", "text/x-sql") -> "sql"
            mime in setOf("application/yaml", "application/x-yaml", "text/yaml") -> "yaml"
            mime == "application/toml" -> "toml"; mime == "text/markdown" -> "markdown"
            firstLine.startsWith("#!") && firstLine.contains("python") -> "python"
            firstLine.startsWith("#!") && listOf("sh", "bash", "zsh").any { firstLine.contains(it) } -> "shell"
            else -> "plain"
        }
    }
    private val slashLanguages = setOf("c", "cpp", "csharp", "java", "kotlin", "javascript", "typescript", "json", "css")
    private val hashLanguages = setOf("python", "shell", "yaml", "toml", "ini")
    private val backtickLanguages = setOf("javascript", "typescript", "shell")
    private val keyLanguages = setOf("yaml", "toml", "ini")
    private val noCommonKeywords = setOf("json", "yaml", "toml", "ini", "markdown", "sql", "shell")
    private val commonKeywords = ("abstract as async await break case catch class const constructor continue data default def " +
        "del do elif else enum except export extends final finally for from fun function global if implements import in interface " +
        "internal is lambda let native new nonlocal object of open operator out override package pass private protected public " +
        "raise record return sealed static struct super suspend switch synchronized this throw throws trait try type typeof " +
        "val var virtual void volatile when while with yield namespace using typedef union sizeof inline extern template").split(' ').toSet()
    private val sqlKeywords = "select from where and or not join left right inner outer on as insert into values update set delete create table drop alter group by order asc desc having limit offset union all distinct null is like between exists primary key references index begin end commit rollback case when then else".split(' ').toSet()
    private val shellKeywords = "if then else elif fi for while until do done case esac in function select time export local readonly declare return break continue".split(' ').toSet()
    private val constants = setOf("true", "false", "null", "none", "nil", "undefined", "nan", "infinity")
    private val types = setOf("int", "long", "short", "byte", "char", "boolean", "bool", "float", "double", "string", "number", "any", "never", "unit", "list", "dict", "set", "tuple")
    fun highlight(text: String, language: String, initial: SyntaxState = SyntaxState(), contextComplete: Boolean = true,
        maxTokens: Int = 8192, cancellation: CancellationToken = CancellationToken.NONE): SyntaxHighlight {
        require(language in languages && text.length <= 262144 && maxTokens in 1..16384)
        cancellation.check()
        if (language == "plain") return SyntaxHighlight(emptyList(), initial, false, contextComplete)
        val suffix = when (language) { "javascript" -> "js"; "typescript" -> "ts"; "csharp" -> "cs"; else -> language }
        val rootScope = when (language) { "html" -> "text.html.basic"; "xml" -> "text.xml"; "markdown" -> "text.html.markdown"; else -> "source.$suffix" }
        val ranges = mutableListOf<ScopedTextRange>(); var i = 0; var terminator = initial.terminator
        var regionScope = initial.scope; var escaped = initial.escaped; var inTag = initial.inTag
        val xml = language == "html" || language == "xml"
        var pendingStart: Int? = null
        fun add(start: Int, end: Int, scope: String) {
            if (end > start) ranges += ScopedTextRange(start, end, listOf(rootScope, "$scope.$suffix"))
        }
        fun begins(value: String, at: Int = i) = text.startsWith(value, at)
        fun lineEnd(start: Int): Int { val end = text.indexOf('\n', start); return if (end < 0) text.length else end }
        while (i < text.length && ranges.size < maxTokens) {
            if (i % 256 == 0) cancellation.check()
            val start = pendingStart ?: i; pendingStart = null
            if (terminator != null) {
                val ender = terminator!!
                while (i < text.length) {
                    if (i % 256 == 0) cancellation.check()
                    if (escaped) { escaped = false; i++; continue }
                    if (regionScope?.startsWith("string") == true && text[i] == '\\') { escaped = true; i++; continue }
                    if (begins(ender)) {
                        if (language == "sql" && ender == "'" && begins("''")) { i += 2; continue }
                        i += ender.length; terminator = null; break
                    }
                    i++
                }
                val scope = if (language == "json" && terminator == null && text.getOrNull(text.indexOfFirstFrom(i) { !it.isWhitespace() }) == ':')
                    "variable.other.property" else regionScope ?: "string"
                add(start, i, scope); continue
            }
            val c = text[i]
            if (xml && begins("<!--")) { i += 4; terminator = "-->"; regionScope = "comment.block"; add(start, i, regionScope!!); continue }
            if (xml && begins("<![CDATA[")) { i += 9; terminator = "]]>"; regionScope = "string.unquoted.cdata"; add(start, i, regionScope!!); continue }
            val slashComments = language in slashLanguages
            if (slashComments && begins("/*")) { i += 2; terminator = "*/"; regionScope = "comment.block"; add(start, i, regionScope!!); continue }
            if ((slashComments && begins("//")) || (language == "sql" && begins("--")) ||
                (language in hashLanguages && (c == '#' || language == "ini" && c == ';'))) {
                i = lineEnd(i); add(start, i, "comment.line"); continue
            }
            if (language == "markdown") {
                if (begins("```") || begins("~~~")) { terminator = text.substring(i, i + 3); regionScope = "markup.raw.block"; i += 3; add(start, i, regionScope!!); continue }
                if (c == '#' && (i == 0 || text[i - 1] == '\n')) { i = lineEnd(i); add(start, i, "markup.heading"); continue }
                val marker = when { begins("**") -> "**"; begins("__") -> "__"; c == '`' -> "`"; c == '*' || c == '_' -> c.toString(); else -> null }
                if (marker != null && !text.getOrNull(i + marker.length).let { it == null || it.isWhitespace() }) {
                    val end = text.indexOf(marker, i + marker.length)
                    if (end >= 0 && end < lineEnd(i)) {
                        i = end + marker.length; add(start, i, if (marker == "`") "markup.raw.inline" else if (marker.length == 2) "markup.bold" else "markup.italic"); continue
                    }
                }
            }
            if (xml && c == '<') {
                i++; if (text.getOrNull(i) == '/' || text.getOrNull(i) == '?' || text.getOrNull(i) == '!') i++
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "_:-")) i++
                inTag = true; add(start, i, "entity.name.tag"); continue
            }
            if (xml && c == '>') { inTag = false; i++; continue }
            if (xml && c == '&') {
                val end = text.indexOf(';', i)
                if (end in (i + 1)..minOf(text.lastIndex, i + 32)) { i = end + 1; add(start, i, "constant.character.entity"); continue }
            }
            val stringQuote = c == '"' || c == '\'' && language != "json" || c == '`' && language in backtickLanguages
            if (stringQuote && (!xml || inTag) && language != "markdown") {
                terminator = if (language in setOf("python", "kotlin") && begins(c.toString().repeat(3))) c.toString().repeat(3) else c.toString()
                i += terminator!!.length; regionScope = "string.quoted"
                if (i == text.length) add(start, i, regionScope!!) else pendingStart = start
                continue
            }
            if (c.isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                i++; while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "._" || text[i] in "+-" && text[i - 1] in "eE")) i++
                add(start, i, "constant.numeric"); continue
            }
            if (c.isLetter() || c == '_' || c == '$') {
                i++; while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "_$-" && (xml || language in setOf("yaml", "css")) || text[i] == '_' || text[i] == '$')) i++
                val word = text.substring(start, i); val lower = word.lowercase()
                val next = text.indexOfFirstFrom(i) { !it.isWhitespace() }
                val scope = when {
                    xml && inTag -> "entity.other.attribute-name"
                    xml -> null
                    language in keyLanguages && next < text.length && text[next] in ":=" -> "variable.other.property"
                    lower in constants -> "constant.language"
                    language == "sql" && lower in sqlKeywords || language == "shell" && word in shellKeywords -> "keyword.control"
                    language !in noCommonKeywords && word in commonKeywords -> "keyword.control"
                    lower in types && language !in setOf("json", "markdown") -> "support.type"
                    next < text.length && text[next] == '(' && language !in setOf("json", "markdown", "yaml") -> "entity.name.function"
                    else -> null
                }
                if (scope != null) add(start, i, scope)
                continue
            }
            i++
        }
        return SyntaxHighlight(ranges, SyntaxState(terminator, regionScope, escaped, inTag), i < text.length, contextComplete)
    }
    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        var i = start; while (i < length && !predicate(this[i])) i++; return i
    }
}
