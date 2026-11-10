package com.infinifolder.viewer

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** CommonMark plus the GFM features commonly used in README and project documents. */
object MarkdownRenderer {
    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
    )
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .build()

    fun renderToHtml(markdown: String, isDark: Boolean = true): String {
        val body = renderer.render(parser.parse(markdown))
        val bg = if (isDark) "#141714" else "#F5F4EF"
        val fg = if (isDark) "#E0E5DC" else "#20231F"
        val codeBg = if (isDark) "#202620" else "#E5E8E0"
        val border = if (isDark) "#333C32" else "#D0D6CA"
        val linkColor = if (isDark) "#81C784" else "#2E7D32"
        val quoteFg = if (isDark) "#9BA994" else "#687363"
        val quoteBg = if (isDark) "#1A201A" else "#EDF0E8"

        return """
            <!doctype html>
            <html><head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
              <style>
                body { background:$bg; color:$fg; font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; line-height:1.6; padding:16px; overflow-wrap:anywhere; }
                h1,h2,h3,h4,h5,h6 { color:$fg; margin-top:1.2em; margin-bottom:.5em; font-weight:600; border-bottom:1px solid $border; padding-bottom:.3em; }
                a { color:$linkColor; text-decoration:underline; }
                code { background:$codeBg; padding:2px 6px; border-radius:4px; font-family:monospace; font-size:.9em; }
                pre { background:$codeBg; padding:12px; border-radius:6px; overflow-x:auto; border:1px solid $border; }
                pre code { background:none; padding:0; }
                blockquote { margin:.8em 0; padding:.5em 1em; color:$quoteFg; border-left:4px solid $linkColor; background:$quoteBg; }
                table { border-collapse:collapse; width:100%; margin:1em 0; }
                th,td { border:1px solid $border; padding:8px 12px; text-align:left; vertical-align:top; }
                th { background:$codeBg; font-weight:bold; }
                img { max-width:100%; height:auto; border-radius:4px; margin:8px 0; }
                hr { border:0; height:1px; background:$border; margin:24px 0; }
                ul,ol { padding-left:24px; margin:8px 0; }
                li { margin-bottom:4px; }
                .task-list-item { list-style:none; }
                .task-list-item input { margin:0 .45em 0 -1.25em; }
              </style>
            </head><body>$body</body></html>
        """.trimIndent()
    }
}
