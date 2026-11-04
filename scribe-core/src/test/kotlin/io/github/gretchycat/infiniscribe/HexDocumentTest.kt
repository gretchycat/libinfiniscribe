package io.github.gretchycat.infiniscribe

import io.github.gretchycat.infinistream.PositionalReadable
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

class HexDocumentTest {
    private fun document(data: ByteArray, shortRead: Int = Int.MAX_VALUE) = HexDocument(object : PositionalReadable {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(length, shortRead, data.size - position.toInt())
            data.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
    }, data.size.toLong())
    private fun failure(action: () -> Unit) {
        try { action(); fail("Expected invalid input") } catch (_: IllegalArgumentException) { }
    }
    @Test fun shortReadsFillRequestedRange() {
        assertArrayEquals(byteArrayOf(2, 3, 4), document(byteArrayOf(1, 2, 3, 4), 1).read(1, 3))
    }
    @Test fun prematureEofIsNotZeroFilled() {
        val doc = HexDocument(object : PositionalReadable {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = -1
        }, 10)
        try { doc.read(0, 1); fail("Truncation must be visible") } catch (_: EOFException) { }
    }
    @Test fun overwriteIsSparseAndDoesNotTouchSource() {
        val original = byteArrayOf(1, 2, 3, 4)
        val doc = document(original)
        doc.overwrite(1, byteArrayOf(8, 9))
        assertArrayEquals(byteArrayOf(1, 8, 9, 4), doc.read(0, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), original)
        assertEquals(2, doc.changedCount)
        assertArrayEquals(original, doc.read(0, 4, patched = false))
    }
    @Test fun undoRedoAndRevertingOriginalByte() {
        val doc = document(byteArrayOf(1, 2, 3))
        doc.overwrite(1, byteArrayOf(8)); doc.overwrite(1, byteArrayOf(2))
        assertEquals(0, doc.changedCount)
        doc.undo(); assertEquals(8.toByte(), doc.read(1, 1)[0])
        doc.undo(); assertEquals(0, doc.changedCount)
        doc.redo(); assertEquals(8.toByte(), doc.read(1, 1)[0])
        doc.overwrite(0, byteArrayOf(7)); assertFalse(doc.canRedo)
    }
    @Test fun replacementCannotGrowFileOrAcceptEmptyBytes() {
        val doc = document(ByteArray(3))
        failure { doc.overwrite(2, byteArrayOf(1, 2)) }
        failure { doc.overwrite(-1, byteArrayOf(1)) }
        failure { doc.overwrite(0, byteArrayOf()) }
        failure { doc.overwrite(0, ByteArray(4097)) }
    }
    @Test fun restoreValidatesOffsetsAndClearsHistory() {
        val doc = document(ByteArray(3))
        doc.restore(mapOf(2L to 7.toByte()))
        assertEquals(7.toByte(), doc.read(2, 1)[0]); assertFalse(doc.canUndo)
        failure { doc.restore(mapOf(3L to 1.toByte())) }
    }
    @Test fun searchCrossesBlockBoundaryAndIncludesEdits() {
        val bytes = ByteArray(65550)
        byteArrayOf(1, 2, 1, 2, 3).copyInto(bytes, 65534)
        val doc = document(bytes, 37)
        assertEquals(65534L, doc.find(byteArrayOf(1, 2, 1, 2, 3), 0))
        doc.overwrite(65538, byteArrayOf(4))
        assertNull(doc.find(byteArrayOf(1, 2, 1, 2, 3), 0))
        assertEquals(65534L, doc.find(byteArrayOf(1, 2, 1, 2, 4), 0))
    }
    @Test fun overlappingAndUnalignedMatches() {
        val doc = document(byteArrayOf(8, 1, 1, 1, 1, 9))
        assertEquals(1L, doc.find(byteArrayOf(1, 1), 0))
        assertEquals(2L, doc.find(byteArrayOf(1, 1), 2))
        assertNull(doc.find(byteArrayOf(1, 1), 4))
    }
    @Test fun cancellationWorksForSearchAndOutput() {
        val doc = document(ByteArray(100))
        try { doc.find(byteArrayOf(4), 0, cancelled = { true }); fail() } catch (_: InterruptedIOException) { }
        try { doc.writeTo(ByteArrayOutputStream(), cancelled = { true }); fail() } catch (_: InterruptedIOException) { }
    }
    @Test fun largeOffsetsRemainLongs() {
        val base = Int.MAX_VALUE.toLong() + 400
        val doc = HexDocument(object : PositionalReadable {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                repeat(length) { buffer[offset + it] = ((position + it) and 255).toByte() }; return length
            }
        }, base + 100)
        assertEquals((base and 255).toByte(), doc.read(base, 1)[0])
        doc.overwrite(base, byteArrayOf(99)); assertEquals(99.toByte(), doc.read(base, 1)[0])
    }
    @Test fun outputWritesActualPatchedFile() {
        val file = File.createTempFile("hex-output", ".bin")
        try {
            file.writeBytes(byteArrayOf(10, 20, 30, 40))
            RandomAccessFile(file, "r").use { source ->
                val doc = HexDocument(object : PositionalReadable {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        source.seek(position); return source.read(buffer, offset, length)
                    }
                }, source.length())
                doc.overwrite(3, byteArrayOf(50))
                val output = ByteArrayOutputStream(); doc.writeTo(output)
                assertArrayEquals(byteArrayOf(10, 20, 30, 50), output.toByteArray())
                assertArrayEquals(byteArrayOf(10, 20, 30, 40), file.readBytes())
            }
        } finally { file.delete() }
    }
    @Test fun hexAndBinaryParsersRequireCompleteBytes() {
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), HexEncoding.hex("0xDE AD, BE:EF"))
        assertArrayEquals(byteArrayOf(0x48, 0x69), HexEncoding.binary("01001000 01101001"))
        failure { HexEncoding.hex("F") }; failure { HexEncoding.hex("GG") }
        failure { HexEncoding.binary("101") }; failure { HexEncoding.binary("00000002") }
    }
    @Test fun offsetsAcceptHexAndDecimalWithoutTruncation() {
        assertEquals(4294967296L, HexEncoding.offset("0x100000000"))
        assertEquals(1024L, HexEncoding.offset("1_024"))
        failure { HexEncoding.offset("-1") }; failure { HexEncoding.offset("0xffffffffffffffff") }
    }
    @Test fun integerEndianAndSignedness() {
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), HexEncoding.integer("0x12345678", 32, false, true))
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), HexEncoding.integer("305419896", 32, false, false))
        assertArrayEquals(byteArrayOf(-1, -1), HexEncoding.integer("-1", 16, true, false))
        assertArrayEquals(ByteArray(8) { -1 }, HexEncoding.integer("18446744073709551615", 64, false, true))
        failure { HexEncoding.integer("256", 8, false, false) }
        failure { HexEncoding.integer("128", 8, true, false) }
        failure { HexEncoding.integer("-1", 8, false, true) }
    }
    @Test fun utf8GlyphsRetainByteOffsets() {
        val glyphs = HexEncoding.glyphs("Aé😀Z".toByteArray(), 100, HexEncoding.Text.UTF8)
        assertEquals(listOf(100L, 101L, 103L, 107L), glyphs.map { it.offset })
        assertEquals(listOf(1, 2, 4, 1), glyphs.map { it.byteCount })
        assertEquals("Aé😀Z", glyphs.joinToString("") { it.text })
    }
    @Test fun malformedUtf8AndControlsBecomeEmptyStrings() {
        val bytes = byteArrayOf(65, 0, 10, 13, 9, 0xc0.toByte(), 0xaf.toByte(), 0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), 66)
        assertEquals("AB", HexEncoding.glyphs(bytes, 0, HexEncoding.Text.UTF8).joinToString("") { it.text })
    }
    @Test fun cp437AndStrictTextEncoding() {
        val bytes = byteArrayOf(0x41, 0x82.toByte(), 0xDE.toByte(), 0, 127)
        assertEquals("Aé▐", HexEncoding.glyphs(bytes, 0, HexEncoding.Text.CP437).joinToString("") { it.text })
        assertArrayEquals(byteArrayOf(0x82.toByte()), HexEncoding.text("é", HexEncoding.Text.CP437))
        assertArrayEquals("é".toByteArray(), HexEncoding.text("é", HexEncoding.Text.UTF8))
        try { HexEncoding.text("😀", HexEncoding.Text.CP437); fail() } catch (_: java.nio.charset.CharacterCodingException) { }
    }
    @Test fun biosLastByteCorrectionForBothTargets() {
        for (target in listOf(0, 255)) {
            val bytes = ByteArray(65536) { (it * 17).toByte() }
            val doc = document(bytes)
            val checksum = doc.checksums()
            val old = doc.read(doc.length - 1, 1)[0].toInt() and 255
            val next = (target - checksum.sum8 + old) and 255
            doc.overwrite(doc.length - 1, byteArrayOf(next.toByte()))
            assertEquals(target, doc.checksums().sum8)
            assertTrue(doc.changes().keys.all { it == doc.length - 1 })
            assertArrayEquals(bytes, doc.read(0, bytes.size, patched = false))
        }
    }
    @Test fun checksumsUseRequestedRangeAndKnownVectors() {
        val doc = document("x123456789y".toByteArray())
        val info = doc.checksums(1, 10)
        assertEquals(0xcbf43926L, info.crc32)
        assertEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225", info.sha256)
        assertEquals((1..9).sumOf { it + 48 } and 255, info.sum8)
        failure { doc.checksums(5, 4) }
    }
    @Test fun emptyFileHasNoMatchesAndValidEmptyChecksum() {
        val doc = document(byteArrayOf())
        assertArrayEquals(byteArrayOf(), doc.read(0, 10))
        assertNull(doc.find(byteArrayOf(1), 0))
        assertEquals(0, doc.checksums().sum8)
    }
    @Test fun fillsEightSixteenTwentyFourAndThirtyTwoBitChunks() {
        for (bits in listOf(8, 16, 24, 32)) for (little in listOf(false, true)) {
            val value = when (bits) { 8 -> "0x12"; 16 -> "0x1234"; 24 -> "0x123456"; else -> "0x12345678" }
            val unit = HexEncoding.integer(value, bits, false, little)
            val doc = document(ByteArray(unit.size * 5 + 2) { 0x7f })
            doc.fill(1, 1L + unit.size * 5, unit)
            val expected = byteArrayOf(0x7f) + ByteArray(unit.size * 5) { unit[it % unit.size] } + byteArrayOf(0x7f)
            assertArrayEquals(expected, doc.read(0, expected.size))
            doc.undo(); assertArrayEquals(ByteArray(expected.size) { 0x7f }, doc.read(0, expected.size))
            assertFalse(doc.canUndo)
            doc.redo(); assertArrayEquals(expected, doc.read(0, expected.size))
        }
    }
    @Test fun fillsPreservePartialLastRangesAndRejectPartialChunksAtomically() {
        val doc = document(ByteArray(17))
        doc.overwrite(0, byteArrayOf(7))
        failure { doc.fill(1, 17, byteArrayOf(1, 2, 3)) }
        failure { doc.fill(0, 18, byteArrayOf(1)) }
        failure { doc.fill(0, 0, byteArrayOf(1)) }
        failure { doc.fill(0, 16, byteArrayOf()) }
        assertEquals(mapOf(0L to 7.toByte()), doc.changes())
    }
    @Test fun rangeFillCrossesBlocksAsOneUndoUnit() {
        val bytes = ByteArray(192 * 1024)
        val doc = document(bytes, 101)
        doc.fill(3, 3 + 128 * 1024L - 2, byteArrayOf(1, 2, 3))
        assertEquals(128 * 1024 - 2, doc.changedCount)
        doc.undo(); assertEquals(0, doc.changedCount); assertFalse(doc.canUndo)
        assertArrayEquals(bytes, doc.read(0, bytes.size))
    }
    @Test fun twentyFourBitValuesHaveCorrectSignAndOrder() {
        assertArrayEquals(byteArrayOf(0x56, 0x34, 0x12), HexEncoding.integer("0x123456", 24, false, true))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0, 0), HexEncoding.integer("-8388608", 24, true, false))
        assertArrayEquals(byteArrayOf(-1, -1, -1), HexEncoding.integer("16777215", 24, false, false))
        failure { HexEncoding.integer("16777216", 24, false, true) }
        failure { HexEncoding.integer("8388608", 24, true, true) }
    }
    @Test fun fullLimitFillStreamsAndUndoesWithoutAWholeFileCopy() {
        val doc = document(ByteArray(2 * 1024 * 1024))
        doc.fill(0, 1024 * 1024, byteArrayOf(1, 2, 3, 4))
        assertEquals(1024 * 1024, doc.changedCount)
        assertArrayEquals(byteArrayOf(3, 4, 1, 2), doc.read(65534, 4))
        failure { doc.overwrite(1024 * 1024, byteArrayOf(1)) }
        assertEquals(1024 * 1024, doc.changedCount)
        doc.undo(); assertEquals(0, doc.changedCount)
    }
    @Test fun journalEntryStreamingRestoresPageAndLargeOffsetPatches() {
        val doc = document(ByteArray(9000))
        doc.restoreEntries(sequenceOf(0L to 1.toByte(), 4095L to 2.toByte(), 4096L to 3.toByte(), 8999L to 4.toByte()))
        assertEquals(setOf(4095L, 4096L), doc.changedOffsets(4094, 4098))
        assertArrayEquals(byteArrayOf(0, 2, 3, 0), doc.read(4094, 4))
        assertEquals(4, doc.changes().size)
        assertEquals(4.toByte(), doc.changes()[8999L])
    }
}
