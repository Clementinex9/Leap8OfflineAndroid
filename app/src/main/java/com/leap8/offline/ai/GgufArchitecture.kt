package com.leap8.offline.ai

import java.io.File
import java.io.RandomAccessFile

/** Bounded metadata probe only; never maps tensor data or trusts the filename. */
internal fun ggufArchitecture(file: File?): String? = runCatching {
    requireNotNull(file)
    RandomAccessFile(file, "r").use { input ->
        fun bounded() { require(input.filePointer <= 1024 * 1024) }
        fun u32(): Long { bounded(); return Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL }
        fun u64(): Long { bounded(); return java.lang.Long.reverseBytes(input.readLong()).also { require(it >= 0) } }
        fun string(): String { val n = u64(); require(n <= 65536); bounded(); return ByteArray(n.toInt()).also(input::readFully).toString(Charsets.UTF_8) }
        fun skipValue(type: Long, depth: Int) {
            require(depth <= 2)
            when (type.toInt()) {
                0, 1, 7 -> input.skipBytes(1)
                2, 3 -> input.skipBytes(2)
                4, 5, 6 -> input.skipBytes(4)
                10, 11, 12 -> input.skipBytes(8)
                8 -> string()
                9 -> { val element = u32(); val count = u64(); require(count <= 65536); repeat(count.toInt()) { skipValue(element, depth + 1); bounded() } }
                else -> error("Unknown GGUF metadata type")
            }
            bounded()
        }
        require(input.readInt() == 0x47475546 && u32() == 3L)
        u64()
        val entries = u64(); require(entries <= 4096)
        repeat(entries.toInt()) {
            val key = string(); val type = u32()
            if (key == "general.architecture") { require(type == 8L); return@use string().take(64) }
            skipValue(type, 0)
        }
        null
    }
}.getOrNull()

internal fun requiresModernNpuRuntime(architecture: String?) = architecture == "qwen35"
