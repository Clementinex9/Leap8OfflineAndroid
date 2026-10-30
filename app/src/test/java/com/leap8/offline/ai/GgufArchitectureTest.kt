package com.leap8.offline.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.nio.file.Files

class GgufArchitectureTest {
    @Test fun readsArchitectureFromMetadataRatherThanFilename() {
        val file = Files.createTempFile("qwen3-4b-misleading", ".gguf").toFile()
        try {
            DataOutputStream(file.outputStream()).use { out ->
                fun u32(n: Int) = out.writeInt(Integer.reverseBytes(n))
                fun u64(n: Long) = out.writeLong(java.lang.Long.reverseBytes(n))
                fun str(s: String) { val bytes = s.toByteArray(); u64(bytes.size.toLong()); out.write(bytes) }
                out.writeInt(0x47475546); u32(3); u64(0); u64(2)
                str("general.type"); u32(8); str("model")
                str("general.architecture"); u32(8); str("qwen35")
            }
            assertEquals("qwen35", ggufArchitecture(file))
            assertTrue(requiresModernNpuRuntime(ggufArchitecture(file)))
            assertFalse(requiresModernNpuRuntime("qwen3"))
        } finally { file.delete() }
    }
    @Test fun truncatedAndMissingMetadataNeverCrashTheRouter() {
        val file = Files.createTempFile("truncated-gguf", ".gguf").toFile()
        try { assertNull(ggufArchitecture(file)); assertNull(ggufArchitecture(null)) }
        finally { file.delete() }
    }
}
