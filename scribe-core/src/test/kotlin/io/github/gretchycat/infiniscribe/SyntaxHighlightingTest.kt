package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.CancellationToken
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException

class SyntaxHighlightingTest {
    private fun tokens(text: String, language: String) = SyntaxHighlighting.highlight(text, language).ranges.map { text.substring(it.start, it.end) to it.scopes.last() }
    @Test fun languageDetectionUsesNamesMimeAndShebangs() {
        assertEquals("kotlin", SyntaxHighlighting.detect("TEST.KT"))
        assertEquals("json", SyntaxHighlighting.detect("unknown", "application/json"))
        assertEquals("python", SyntaxHighlighting.detect("script", firstLine = "#!/usr/bin/env python3"))
        assertEquals("plain", SyntaxHighlighting.detect("notes.txt"))
        assertEquals("source.js", SyntaxHighlighting.highlight("const", "javascript").ranges.first().scopes.first())
        assertEquals("text.html.basic", SyntaxHighlighting.highlight("<p>", "html").ranges.first().scopes.first())
    }
    @Test fun stringsAndCommentsShieldEmbeddedKeywordsAndNumbers() {
        val result = tokens("val x = \"if 123\" // return 42\nreturn 7", "kotlin")
        assertTrue(result.any { it.first == "val" && it.second.startsWith("keyword") })
        assertTrue(result.any { it.first == "\"if 123\"" && it.second.startsWith("string") })
        assertTrue(result.any { it.first == "// return 42" && it.second.startsWith("comment") })
        assertFalse(result.any { it.first == "if" || it.first == "42" })
    }
    @Test fun jsonPropertyScopesIncludeQuotedKeysAndEscapedQuotes() {
        val result = tokens("{\"a\\\"b\":true,\"text\":\"🎵日本語\",\"n\":1e-3}", "json")
        assertTrue(result.any { it.first == "\"a\\\"b\"" && it.second.startsWith("variable.other.property") })
        assertTrue(result.any { it.first == "\"🎵日本語\"" && it.second.startsWith("string") })
        assertTrue(result.any { it.first == "1e-3" && it.second.startsWith("constant.numeric") })
    }
    @Test fun blockCommentStateContinuesAcrossChunks() {
        val first = SyntaxHighlighting.highlight("/* begin", "java")
        assertEquals("*/", first.endState.terminator)
        val text = " end */ class Example"
        val next = SyntaxHighlighting.highlight(text, "java", first.endState)
        assertTrue(next.ranges.any { text.substring(it.start, it.end) == " end */" && it.scopes.last().startsWith("comment") })
        assertTrue(next.ranges.any { text.substring(it.start, it.end) == "class" && it.scopes.last().startsWith("keyword") })
        assertNull(next.endState.terminator)
    }
    @Test fun escapedStringAndTripleQuoteStatesSurviveBoundaries() {
        val first = SyntaxHighlighting.highlight("\"text\\", "javascript")
        assertTrue(first.endState.escaped)
        val next = SyntaxHighlighting.highlight("\" remains\" const", "javascript", first.endState)
        assertTrue(next.ranges.last().scopes.last().startsWith("keyword"))
        val python = SyntaxHighlighting.highlight("\"\"\"many\nlines", "python")
        assertEquals("\"\"\"", python.endState.terminator)
        assertNull(SyntaxHighlighting.highlight("\nend\"\"\"", "python", python.endState).endState.terminator)
    }
    @Test fun markupTagsAttributesEntitiesAndCommentsHaveSeparateScopes() {
        val result = tokens("<!-- <bad> --><p class=\"x\">Hi &amp;</p>", "html")
        assertTrue(result.any { it.first == "<p" && it.second.startsWith("entity.name.tag") })
        assertTrue(result.any { it.first == "class" && it.second.startsWith("entity.other.attribute-name") })
        assertTrue(result.any { it.first == "&amp;" && it.second.startsWith("constant.character.entity") })
        assertFalse(result.any { it.first == "<bad" })
    }
    @Test fun markdownHeadingsEmphasisAndCodeFencesAreStyledAsSource() {
        val result = tokens("# Title\n**bold** *italic* `code`\n```js\nconst x=1\n```", "markdown")
        assertTrue(result.any { it.second.startsWith("markup.heading") })
        assertTrue(result.any { it.second.startsWith("markup.bold") })
        assertTrue(result.any { it.second.startsWith("markup.italic") })
        assertTrue(result.any { it.second.startsWith("markup.raw.block") })
    }
    @Test fun yamlSqlShellAndCssUseTheirOwnLexicalConventions() {
        assertTrue(tokens("enabled: true # note", "yaml").any { it.first == "enabled" && it.second.startsWith("variable.other.property") })
        assertTrue(tokens("SELECT * FROM x -- comment", "sql").any { it.first == "SELECT" && it.second.startsWith("keyword") })
        assertTrue(tokens("if test; then # hi", "shell").any { it.second.startsWith("comment") })
        assertTrue(tokens("/* color */ p { width: 12px; }", "css").any { it.second.startsWith("constant.numeric") })
    }
    @Test fun everyRangeIsOrderedNonoverlappingAndUsesUtf16OffsetsWithoutChangingText() {
        val text = "val é = \"🎵日本語\"; // 😀\n".repeat(500)
        val original = text.toByteArray()
        val result = SyntaxHighlighting.highlight(text, "kotlin")
        assertFalse(result.truncated)
        var end = 0
        result.ranges.forEach { range -> assertTrue(range.start >= end && range.end <= text.length && range.end > range.start); end = range.end }
        assertArrayEquals(original, text.toByteArray())
    }
    @Test fun tokenAndInputBudgetsRemainBoundedAndIncompleteContextIsExplicit() {
        val result = SyntaxHighlighting.highlight("1 ".repeat(10000), "json", contextComplete = false, maxTokens = 25)
        assertEquals(25, result.ranges.size); assertTrue(result.truncated); assertFalse(result.contextComplete)
        assertThrows(IllegalArgumentException::class.java) { SyntaxHighlighting.highlight("x".repeat(262145), "java") }
    }
    @Test fun cancellationPlainTextAndUnknownLanguagesDoNotInventTokens() {
        assertThrows(InterruptedIOException::class.java) { SyntaxHighlighting.highlight("x", "java", cancellation = CancellationToken { true }) }
        assertTrue(SyntaxHighlighting.highlight("plain <text> 123", "plain").ranges.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { SyntaxHighlighting.highlight("x", "unknown") }
    }
    @Test fun unclosedInputStaysReadableAndNeverEmitsOutOfBoundsRanges() {
        for (text in listOf("\"", "'", "/*", "<!--", "```", "{\"key\":\"unfinished")) {
            for (language in listOf("json", "java", "html", "markdown")) {
                val result = SyntaxHighlighting.highlight(text, language)
                assertTrue(result.ranges.all { it.start >= 0 && it.end <= text.length })
            }
        }
    }
}
