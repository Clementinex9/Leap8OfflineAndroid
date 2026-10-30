package com.leap8.offline.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class LocalAiIsolationManifestTest {
    @Test
    fun nativeInferenceIsDeclaredOutsideTheUiProcess() {
        val manifest = readSource("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.byteInputStream())
        val services = document.getElementsByTagName("service")
        val expected = mapOf(
            ".ai.LocalAiProcessService" to ":leap8_cpu",
            ".ai.LocalAiGpuProcessService" to ":leap8_gpu",
            ".ai.LocalAiNpuProcessService" to ":leap8_npu",
            ".speech.NaturalVoiceProcessService" to ":leap8_speech"
        )
        val found = mutableSetOf<String>()
        for (index in 0 until services.length) {
            val service = services.item(index) as org.w3c.dom.Element
            val name = service.getAttributeNS(ANDROID_NAMESPACE, "name")
            if (name in expected) {
                assertEquals(expected[name], service.getAttributeNS(ANDROID_NAMESPACE, "process"))
                assertEquals("false", service.getAttributeNS(ANDROID_NAMESPACE, "exported"))
                found.add(name)
            }
        }
        assertEquals(expected.keys, found)

        val uiRouter = readSource("src/main/java/com/leap8/offline/ai/OfflineAiService.kt")
        assertFalse(uiRouter.contains("LlamaNative"))
        assertFalse(uiRouter.contains("LlamaEngine"))

        val scoring = readSource("src/main/java/com/leap8/offline/domain/EssayScoring.kt")
        assertFalse(scoring.contains("QairtEssayBridge"))
        assertFalse(scoring.contains("Class.forName"))

        val worker = readSource("src/main/java/com/leap8/offline/ai/LocalAiProcessService.kt")
        assertTrue(worker.contains("LlamaEngine"))
    }

    private fun sourceFile(relative: String): Path = listOf(
        Path.of("app", relative),
        Path.of(relative)
    ).firstOrNull(Files::isRegularFile) ?: error("Source file was not found: $relative")

    private fun readSource(relative: String): String =
        String(Files.readAllBytes(sourceFile(relative)), Charsets.UTF_8)

    private companion object { const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android" }
}
