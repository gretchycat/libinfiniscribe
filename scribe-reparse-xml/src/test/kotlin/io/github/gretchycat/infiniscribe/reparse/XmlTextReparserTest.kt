package io.github.gretchycat.infiniscribe.reparse

import io.github.gretchycat.infinistream.CancellationToken
import org.junit.Assert.*
import org.junit.Test

class XmlTextReparserTest {
    @Test fun keepsMixedContentAndWhitespaceAndNamespace() {
        val input = "<r xmlns:p=\"urn:test\"><p:a/><p>Hello <b>world</b> !</p><s xml:space=\"preserve\">  <a/>  </s><c><![CDATA[a<b]]></c><!--keep--></r>"
        val out = XmlTextReparser.format(input, CancellationToken.NONE)
        assertTrue(out.contains("\n  <p:a/>"))
        assertTrue(out.contains("xmlns:p=\"urn:test\""))
        assertTrue(out.contains("<p>Hello <b>world</b> !</p>"))
        assertTrue(out.contains("  <a/>  </s>"))
        assertTrue(out.contains("<![CDATA[a<b]]>"))
        assertTrue(out.contains("<!--keep-->"))
        assertEquals(out, XmlTextReparser.format(out, CancellationToken.NONE))
    }
    @Test fun rejectsMalformedXmlAndDtd() {
        assertThrows(Exception::class.java) { XmlTextReparser.format("<a><b></a>", CancellationToken.NONE) }
        assertThrows(IllegalArgumentException::class.java) {
            XmlTextReparser.format("<!DOCTYPE a SYSTEM 'file:///not-read'><a/>", CancellationToken.NONE)
        }
    }
}
