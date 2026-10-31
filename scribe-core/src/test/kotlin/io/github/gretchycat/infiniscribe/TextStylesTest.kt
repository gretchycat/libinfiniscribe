package io.github.gretchycat.infiniscribe

import org.junit.Assert.*
import org.junit.Test

class TextStylesTest {
    @Test fun standardTokenColorsAndNamespacedFontPropertiesAreParsed() {
        val theme = TextThemeJson.parse("""{"name":"Custom","colors":{"editor.foreground":"#123456","editor.background":"#FFF"},
            "infiniscribe":{"version":1,"fontSize":18,"fontFamily":"serif"},
            "tokenColors":[{"scope":["keyword","storage"],"settings":{"foreground":"#f08","fontStyle":"bold italic underline","infiniscribe":{"fontSize":20,"fontFamily":"sans-serif"}}}]}""")
        assertEquals(18f, theme.defaults.fontSize!!)
        val style = theme.resolve(listOf("source.kotlin", "keyword.control.kotlin"))
        assertEquals(TextThemeJson.parseColor("#f08"), style.foreground)
        assertEquals(20f, style.fontSize!!); assertEquals("sans-serif", style.fontFamily)
        assertEquals(true, style.bold); assertEquals(true, style.italic); assertEquals(true, style.underline)
    }
    @Test fun cssRgbaColorsConvertToPortableArgbWithoutSwappingAlpha() {
        assertEquals(0x44112233, TextThemeJson.parseColor("#1234"))
        assertEquals(0x80445566.toInt(), TextThemeJson.parseColor("#44556680"))
        assertEquals(0xff112233.toInt(), TextThemeJson.parseColor("#123"))
        assertEquals("#44556680", TextThemeJson.colorString(0x80445566.toInt()))
    }
    @Test fun specificSelectorsOverrideOnlyTheirDeclaredProperties() {
        val theme = TextTheme("test", TextThemeJson.DARK.defaults, listOf(
            TextStyleRule(listOf("comment.line"), TextStyle(italic = false)),
            TextStyleRule(listOf("comment"), TextStyle(foreground = 123, italic = true)),
            TextStyleRule(listOf("source.python comment"), TextStyle(bold = true))))
        val style = theme.resolve(listOf("source.python", "comment.line.python"))
        assertEquals(123, style.foreground); assertEquals(false, style.italic); assertEquals(true, style.bold)
        assertEquals(TextThemeJson.DARK.defaults.fontFamily, style.fontFamily)
    }
    @Test fun emptyFontStyleExplicitlyClearsInheritedEmphasis() {
        val theme = TextThemeJson.parse("""{"infiniscribe":{"fontStyle":"bold italic"},"tokenColors":[{"scope":"comment","settings":{"fontStyle":""}}]}""")
        val style = theme.resolve(listOf("comment.block"))
        assertEquals(false, style.bold); assertEquals(false, style.italic)
    }
    @Test fun exportedDescriptorsRoundTripIncludingPartialInheritanceAndCustomFonts() {
        for (theme in listOf(TextThemeJson.DARK, TextThemeJson.LIGHT, TextTheme("custom", TextThemeJson.DARK.defaults.copy(fontSize = 22f, fontFamily = "serif", bold = true),
            listOf(TextStyleRule(listOf("comment", "string"), TextStyle(italic = true, foreground = 0x11123456, fontSize = 16f)))))) {
            assertEquals(theme, TextThemeJson.parse(TextThemeJson.format(theme)))
        }
    }
    @Test fun unsupportedSelectorsInvalidStylesAndUnboundedDescriptorsFailExplicitly() {
        for (json in listOf("[]", "{" , """{"infiniscribe":{"version":2}}""", """{"infiniscribe":{"fontSize":999}}""",
            """{"tokenColors":"external.json"}""", """{"tokenColors":[{"scope":"comment - string"}]}""", """{"colors":{"editor.foreground":"red"}}""")) {
            assertThrows(Exception::class.java) { TextThemeJson.parse(json) }
        }
        assertThrows(IllegalArgumentException::class.java) { TextThemeJson.parse(" ".repeat(262145)) }
        assertThrows(IllegalArgumentException::class.java) { TextStyle(fontSize = Float.NaN) }
    }
    @Test fun commaAndDescendantSelectorsUseStandardDottedScopePrefixes() {
        val theme = TextThemeJson.parse("""{"tokenColors":[{"scope":"keyword, source.json variable.other.property","settings":{"foreground":"#010203"}}]}""")
        assertEquals(0xff010203.toInt(), theme.resolve(listOf("source.json", "variable.other.property.json")).foreground)
        assertNotEquals(0xff010203.toInt(), theme.resolve(listOf("source.other", "variable.other.property.other")).foreground)
    }
    @Test fun omittedPropertiesInheritAndExplicitFalseIsRetained() {
        val base = TextStyle(foreground = 1, bold = true, fontSize = 18f)
        assertEquals(TextStyle(1, bold = false, fontSize = 18f), TextStyle(bold = false).over(base))
        assertEquals(base, TextStyle().over(base))
    }
}
