package com.leap8.offline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

class VocabularyCatalogAssetTest {
    @Test fun packagedSourceContainsTheFullVerifiedWordbook() {
        val asset = listOf(
            Path.of("app", "src", "main", "assets", "datasets", "ielts_vocabulary.tsv.bin"),
            Path.of("src", "main", "assets", "datasets", "ielts_vocabulary.tsv.bin")
        ).firstOrNull(Files::isRegularFile) ?: error("IELTS vocabulary source asset was not found")

        val rows = GZIPInputStream(Files.newInputStream(asset)).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filterNot { it.startsWith('#') || it.isBlank() }
                .map { it.split('\t') }
                .toList()
        }
        assertEquals(5_040, rows.size)
        assertTrue(rows.all { it.size == 8 })
        val taggedWords = rows.map { it[1].lowercase() }.toSet()
        assertEquals(5_040, taggedWords.size)

        val editorialWords = SeedContent.vocabulary.map { it.word.lowercase() }.toSet()
        assertEquals(30, editorialWords.size)
        assertEquals(22, editorialWords.count(taggedWords::contains))
        assertEquals(5_048, (taggedWords + editorialWords).size)
    }
}
