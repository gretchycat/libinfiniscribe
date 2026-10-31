package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.CancellationToken
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.charset.Charset

class EpubXmlInputTest {
    private fun read(data: ByteArray): ByteArray = EpubXmlInput(data.inputStream(), Long.MAX_VALUE, CancellationToken.NONE).readBytes()
    @Test fun externalIdentifiersCommentsCdataAndQuotedBracketsRemainUnchanged() {
        val xml = "<?xml version='1.0'?><!-- <!DOCTYPE fake [ --> <!DOCTYPE package SYSTEM 'file[bracket].dtd'><package><![CDATA[<!DOCTYPE fake [ ]>]]></package>"
        assertArrayEquals(xml.toByteArray(), read(xml.toByteArray()))
    }
    @Test fun internalDtdSubsetsAreRejectedAcrossSupportedUnicodeEncodings() {
        val xml = "<!DOCTYPE package SYSTEM 'offline.dtd' [<!ENTITY x 'expanded'>]><package>&x;</package>"
        for (encoding in listOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            val bytes = xml.toByteArray(Charset.forName(encoding))
            assertThrows(encoding, IOException::class.java) { read(bytes) }
        }
    }
    @Test fun ordinaryUnicodeResourcesAndSingleByteChunksKeepOriginalBytes() {
        val xml = "<?xml version='1.0'?><package><title>日本語 — Café</title></package>"
        for (encoding in listOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            val bytes = xml.toByteArray(Charset.forName(encoding))
            val input = EpubXmlInput(bytes.inputStream(), Long.MAX_VALUE, CancellationToken.NONE)
            val output = java.io.ByteArrayOutputStream()
            while (true) { val b = input.read(); if (b < 0) break; output.write(b) }
            assertArrayEquals(encoding, bytes, output.toByteArray())
        }
    }
    @Test fun optionalByteBudgetAllowsExactSizeAndRejectsAnExtraByte() {
        val bytes = "<package/>".toByteArray()
        assertArrayEquals(bytes, EpubXmlInput(bytes.inputStream(), bytes.size.toLong(), CancellationToken.NONE).readBytes())
        assertThrows(IOException::class.java) { EpubXmlInput(bytes.inputStream(), bytes.size.toLong() - 1, CancellationToken.NONE).readBytes() }
    }
}
