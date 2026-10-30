package com.leap8.qairt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.function.Consumer

class QairtTextAndPathsTest {
    @Test fun utf8RemainsIntactAtEveryPossibleTokenBoundary() {
        val text = "IELTS 中文🙂 café 𠮷 done"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (boundary in 0..bytes.size) {
            val result = StringBuilder()
            val stream = Utf8TokenStream(Consumer { result.append(it) })
            stream.consumer.accept(bytes.copyOfRange(0, boundary))
            stream.consumer.accept(bytes.copyOfRange(boundary, bytes.size))
            stream.finish()
            assertEquals("boundary $boundary", text, result.toString())
        }
    }

    @Test fun oneByteTokensPreserveChineseAndEmoji() {
        val result = StringBuilder()
        val stream = Utf8TokenStream(Consumer { result.append(it) })
        "中文🙂".toByteArray(Charsets.UTF_8).forEach { stream.consumer.accept(byteArrayOf(it)) }
        stream.finish()
        assertEquals("中文🙂", result.toString())
    }

    @Test fun truncatedUtf8IsReplacedOnlyWhenStreamFinishes() {
        val result = StringBuilder()
        val stream = Utf8TokenStream(Consumer { result.append(it) })
        stream.consumer.accept(byteArrayOf(0xf0.toByte(), 0x9f.toByte()))
        assertEquals("", result.toString())
        stream.finish()
        assertEquals("\uFFFD", result.toString())
    }

    @Test fun contextPathsPreserveSubdirectoriesAndRejectEscapes() {
        assertEquals("parts/model.bin", QairtModelBundle.relativePath("parts\\model.bin"))
        listOf("", "../model.bin", "a/../model.bin", "/data/model.bin", "C:\\model.bin", "a//b.bin").forEach {
            assertThrows(IllegalArgumentException::class.java) { QairtModelBundle.relativePath(it) }
        }
    }
}
