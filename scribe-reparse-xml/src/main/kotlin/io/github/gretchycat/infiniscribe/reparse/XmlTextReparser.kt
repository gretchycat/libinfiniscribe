package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.CancellationToken
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.DocumentBuilderFactory

/** Indents element-only content while retaining mixed text, CDATA, namespaces and xml:space. */
object XmlTextReparser : TextReparser {
    override val name = "XML"
    override val extensions = setOf("xml", "svg", "xsd", "xsl", "xslt", "gpx", "rss", "atom")
    override val mimeTypes = setOf("application/xml", "text/xml")
    override val mimeSuffixes = setOf("+xml")
    override val leadingCharacters = setOf('<')
    override fun format(text: String, cancellation: CancellationToken): String {
        require(!text.contains("<!DOCTYPE")) { "XML with a DOCTYPE is not supported for reparsing" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External XML entities are not supported") }
            setErrorHandler(object : DefaultHandler() {
                override fun error(error: org.xml.sax.SAXParseException) { throw error }
                override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
            })
        }
        val document = ReparseReader(text, cancellation).use { builder.parse(InputSource(it)) }
        val out = ReparseWriter(cancellation)
        fun escape(value: String, attribute: Boolean = false) {
            for (char in value) out.append(when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> if (attribute) "&quot;" else "\""
                '\r' -> "&#13;"
                '\n' -> if (attribute) "&#10;" else "\n"
                '\t' -> if (attribute) "&#9;" else "\t"
                else -> char.toString()
            })
        }
        fun children(node: Node): List<Node> = (0 until node.childNodes.length).map(node.childNodes::item)
        fun emit(node: Node, depth: Int, preserve: Boolean) {
            cancellation.check()
            require(depth <= 128) { "XML nesting exceeds 128 levels" }
            when (node.nodeType) {
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    val space = element.getAttributeNS("http://www.w3.org/XML/1998/namespace", "space")
                    val all = children(node)
                    val mixed = all.any { it.nodeType == Node.CDATA_SECTION_NODE ||
                        (it.nodeType == Node.TEXT_NODE && !it.nodeValue.isNullOrBlank()) }
                    val compact = preserve || space == "preserve" || mixed
                    out.append('<').append(node.nodeName)
                    for (i in 0 until node.attributes.length) {
                        val attr = node.attributes.item(i)
                        out.append(' ').append(attr.nodeName).append("=\"")
                        escape(attr.nodeValue, true)
                        out.append('"')
                    }
                    val content = if (compact) all else all.filterNot {
                        it.nodeType == Node.TEXT_NODE && it.nodeValue.isNullOrBlank()
                    }
                    if (content.isEmpty()) out.append("/>") else {
                        out.append('>')
                        for (child in content) {
                            if (!compact) { out.append('\n'); repeat(depth + 1) { out.append("  ") } }
                            emit(child, depth + 1, compact)
                        }
                        if (!compact) { out.append('\n'); repeat(depth) { out.append("  ") } }
                        out.append("</").append(node.nodeName).append('>')
                    }
                }
                Node.TEXT_NODE -> escape(node.nodeValue)
                Node.CDATA_SECTION_NODE -> out.append("<![CDATA[").append(node.nodeValue).append("]]>")
                Node.COMMENT_NODE -> out.append("<!--").append(node.nodeValue).append("-->")
                Node.PROCESSING_INSTRUCTION_NODE -> {
                    out.append("<?").append(node.nodeName)
                    if (!node.nodeValue.isNullOrEmpty()) out.append(' ').append(node.nodeValue)
                    out.append("?>")
                }
                else -> error("Unsupported XML node ${node.nodeType}")
            }
        }
        // This is an in-memory Unicode display; source encoding declarations belong to the original bytes.
        for (node in children(document)) {
            emit(node, 0, false)
            out.append('\n')
        }
        return out.toString()
    }
}
