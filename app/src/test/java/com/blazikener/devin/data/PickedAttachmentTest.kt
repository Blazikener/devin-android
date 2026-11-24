package com.blazikener.devin.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PickedAttachmentTest {
    @Test fun copiesBytesAtTheLimitAcrossMultipleReads() {
        val bytes = ByteArray(2 * DEFAULT_BUFFER_SIZE) { it.toByte() }
        val result = ByteArrayOutputStream()

        copyAttachment(bytes.inputStream(), result, maxBytes = bytes.size.toLong())

        assertArrayEquals(bytes, result.toByteArray())
    }

    @Test fun checksActualSizeEvenWhenProviderSizeIsUnavailableOrWrong() {
        val input = ByteArrayInputStream(ByteArray(3 * DEFAULT_BUFFER_SIZE))
        val result = ByteArrayOutputStream()
        val limit = DEFAULT_BUFFER_SIZE.toLong()

        assertThrows(AttachmentTooLarge::class.java) { copyAttachment(input, result, maxBytes = limit) }

        assertEquals(limit.toInt(), result.size())
        assertEquals(2 * DEFAULT_BUFFER_SIZE - 1, input.available())
    }
}
