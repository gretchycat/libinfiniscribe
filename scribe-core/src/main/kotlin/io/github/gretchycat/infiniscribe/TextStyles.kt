package io.github.gretchycat.infiniscribe

import com.google.gson.*

/** Nullable properties inherit. Colors are portable ARGB bit patterns, sizes are logical scalable units. */
data class TextStyle(val foreground: Int? = null, val background: Int? = null,
    val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
    val strikethrough: Boolean? = null, val fontFamily: String? = null, val fontSize: Float? = null) {
    init {
        require(fontSize == null || fontSize.isFinite() && fontSize in 6f..96f)
        require(fontFamily == null || fontFamily.isNotBlank() && fontFamily.length <= 128)
    }
    fun over(base: TextStyle) = TextStyle(foreground ?: base.foreground, background ?: base.background,
        bold ?: base.bold, italic ?: base.italic, underline ?: base.underline, strikethrough ?: base.strikethrough,
        fontFamily ?: base.fontFamily, fontSize ?: base.fontSize)
}
data class TextStyleRule(val scopes: List<String>, val style: TextStyle)
data class TextTheme(val name: String, val defaults: TextStyle, val rules: List<TextStyleRule>) {
    /** TextMate dotted scopes and descendant selectors; most-specific rule wins per property. */
    fun resolve(scopes: List<String>): TextStyle {
        var result = defaults
        rules.mapIndexedNotNull { index, rule ->
            val score = rule.scopes.maxOfOrNull { selectorScore(it, scopes) } ?: -1
            if (score >= 0) Triple(score, index, rule.style) else null
        }.sortedWith(compareBy({ it.first }, { it.second })).forEach { result = it.third.over(result) }
        return result
    }
    private fun selectorScore(selector: String, scopes: List<String>): Int {
        val parts = selector.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return -1
        var cursor = scopes.lastIndex; var score = 0
        for (part in parts.asReversed()) {
            while (cursor >= 0 && scopes[cursor] != part && !scopes[cursor].startsWith("$part.")) cursor--
            if (cursor < 0) return -1
            score += part.count { it == '.' } * 100 + part.length + cursor
            cursor--
        }
        return score + (parts.size - 1) * 10000
    }
}

/** VS Code tokenColors/TextMate scopes, with a namespaced extension for font size/family. No grammar import is implied. */
object TextThemeJson {
    fun parse(json: String): TextTheme {
        require(json.length <= 256 * 1024) { "Theme descriptor exceeds 256 KiB" }
        val root = JsonParser.parseString(json).asJsonObject
        require(!root.has("include")) { "Theme includes are not loaded; import a self-contained theme" }
        val colors = root.getAsJsonObject("colors") ?: JsonObject()
        val extra = root.getAsJsonObject("infiniscribe") ?: JsonObject()
        require(!extra.has("version") || extra["version"].asInt == 1) { "Unsupported style descriptor version" }
        var defaults = style(extra)
        defaults = defaults.copy(foreground = color(colors, "editor.foreground") ?: defaults.foreground,
            background = color(colors, "editor.background") ?: defaults.background).over(DARK.defaults)
        val rules = mutableListOf<TextStyleRule>()
        val tokens = root.get("tokenColors")
        require(tokens == null || tokens.isJsonArray) { "tokenColors must be an array; external include files are not loaded" }
        tokens?.asJsonArray?.forEach { item ->
            require(rules.size < 512) { "Too many theme rules" }
            val rule = item.asJsonObject; val scope = rule.get("scope")
            val settings = rule.getAsJsonObject("settings") ?: JsonObject()
            val parsed = style(settings).let { base ->
                settings.getAsJsonObject("infiniscribe")?.let { style(it).over(base) } ?: base
            }
            if (scope == null) defaults = parsed.over(defaults)
            else {
                val selectors = (if (scope.isJsonArray) scope.asJsonArray.map { it.asString } else listOf(scope.asString))
                    .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                require(selectors.all { it.matches(Regex("[A-Za-z0-9_.*-]+(?:\\s+[A-Za-z0-9_.*-]+)*")) && !it.contains('*') && !it.split(' ').contains("-") }) {
                    "Unsupported scope selector; use dotted scopes or descendant selectors"
                }
                rules += TextStyleRule(selectors, parsed)
            }
        }
        return TextTheme(root.get("name")?.asString?.take(128) ?: "Custom", defaults, rules)
    }
    fun format(theme: TextTheme): String {
        val root = JsonObject(); root.addProperty("name", theme.name)
        root.add("colors", JsonObject().apply {
            theme.defaults.foreground?.let { addProperty("editor.foreground", colorString(it)) }
            theme.defaults.background?.let { addProperty("editor.background", colorString(it)) }
        })
        root.add("infiniscribe", encode(theme.defaults).apply {
            remove("infiniscribe")?.asJsonObject?.entrySet()?.forEach { (key, value) -> add(key, value) }
            addProperty("version", 1)
        })
        root.add("tokenColors", JsonArray().apply { theme.rules.forEach { rule -> add(JsonObject().apply {
            add("scope", JsonArray().apply { rule.scopes.forEach { add(it) } })
            add("settings", encode(rule.style))
        }) } })
        return GsonBuilder().setPrettyPrinting().create().toJson(root)
    }
    private fun style(o: JsonObject): TextStyle {
        val flags = o.get("fontStyle")?.asString?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
        require(flags == null || flags.all { it in setOf("bold", "italic", "underline", "strikethrough") }) { "Unknown fontStyle" }
        return TextStyle(color(o, "foreground"), color(o, "background"), o.get("bold")?.asBoolean ?: flags?.contains("bold"), o.get("italic")?.asBoolean ?: flags?.contains("italic"),
            o.get("underline")?.asBoolean ?: flags?.contains("underline"), o.get("strikethrough")?.asBoolean ?: flags?.contains("strikethrough"), o.get("fontFamily")?.asString, o.get("fontSize")?.asFloat)
    }
    private fun encode(s: TextStyle) = JsonObject().apply {
        s.foreground?.let { addProperty("foreground", colorString(it)) }; s.background?.let { addProperty("background", colorString(it)) }
        if (listOf(s.bold, s.italic, s.underline, s.strikethrough).all { it != null }) addProperty("fontStyle",
            listOfNotNull("bold".takeIf { s.bold == true }, "italic".takeIf { s.italic == true },
                "underline".takeIf { s.underline == true }, "strikethrough".takeIf { s.strikethrough == true }).joinToString(" "))
        if (s.fontFamily != null || s.fontSize != null || listOf(s.bold, s.italic, s.underline, s.strikethrough).any { it == null }) add("infiniscribe", JsonObject().apply {
            if (listOf(s.bold, s.italic, s.underline, s.strikethrough).any { it == null }) {
                s.bold?.let { addProperty("bold", it) }; s.italic?.let { addProperty("italic", it) }
                s.underline?.let { addProperty("underline", it) }; s.strikethrough?.let { addProperty("strikethrough", it) }
            }
            s.fontFamily?.let { addProperty("fontFamily", it) }; s.fontSize?.let { addProperty("fontSize", it) }
        })
    }
    private fun color(o: JsonObject, key: String) = o.get(key)?.asString?.let(::parseColor)
    fun parseColor(value: String): Int {
        require(value.matches(Regex("#[0-9a-fA-F]{3,8}")) && value.length in setOf(4, 5, 7, 9)) { "Use #RGB, #RGBA, #RRGGBB or #RRGGBBAA" }
        val digits = value.drop(1).let { if (it.length <= 4) it.flatMap { c -> listOf(c, c) }.joinToString("") else it }
        val rgba = digits.toLong(16)
        return if (digits.length == 6) (0xff000000L or rgba).toInt() else ((rgba ushr 8) or ((rgba and 255) shl 24)).toInt()
    }
    fun colorString(argb: Int): String = "#" + (((argb.toLong() and 0xffffff) shl 8) or (argb.toLong() ushr 24 and 255)).toString(16).padStart(8, '0').uppercase()
    val DARK = TextTheme("Forest dark", TextStyle(0xffe0e5dc.toInt(), 0xff141714.toInt(), false, false, false, false, "monospace", 13f), listOf(
        TextStyleRule(listOf("comment"), TextStyle(foreground = 0xff7ea672.toInt(), italic = true)),
        TextStyleRule(listOf("keyword", "storage"), TextStyle(foreground = 0xffc792ea.toInt())),
        TextStyleRule(listOf("string"), TextStyle(foreground = 0xffc3e88d.toInt())),
        TextStyleRule(listOf("constant"), TextStyle(foreground = 0xfff78c6c.toInt())),
        TextStyleRule(listOf("entity.name", "support", "variable.other.property"), TextStyle(foreground = 0xff82aaff.toInt())),
        TextStyleRule(listOf("markup.heading"), TextStyle(foreground = 0xff82aaff.toInt(), bold = true)),
        TextStyleRule(listOf("markup.bold"), TextStyle(bold = true)), TextStyleRule(listOf("markup.italic"), TextStyle(italic = true)),
        TextStyleRule(listOf("markup.raw"), TextStyle(foreground = 0xffc3e88d.toInt()))))
    val LIGHT = DARK.copy(name = "Paper light", defaults = DARK.defaults.copy(foreground = 0xff242424.toInt(), background = 0xfffafafa.toInt()),
        rules = DARK.rules.map { rule -> rule.copy(style = rule.style.copy(foreground = when (rule.scopes.first()) {
            "comment" -> 0xff557a36.toInt(); "keyword" -> 0xff823ba3.toInt(); "string", "markup.raw" -> 0xff477515.toInt()
            "constant" -> 0xffa14c15.toInt(); "entity.name", "markup.heading" -> 0xff1d59a7.toInt(); else -> rule.style.foreground
        })) })
}
