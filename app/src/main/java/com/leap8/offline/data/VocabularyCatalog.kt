package com.leap8.offline.data

import android.content.Context
import com.leap8.offline.domain.VocabularyCard
import java.util.zip.GZIPInputStream

/** Every IELTS-tagged ECDICT entry, enriched by the 30 editorial SeedContent cards. */
class VocabularyCatalog(context: Context) {
    private val appContext = context.applicationContext

    val cards: List<VocabularyCard> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val editorial = SeedContent.vocabulary.associateBy { it.word.lowercase() }
        val covered = mutableSetOf<String>()
        val result = ArrayList<VocabularyCard>(5_100)
        GZIPInputStream(appContext.assets.open(DATASET)).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filterNot { it.startsWith('#') || it.isBlank() }.forEach { line ->
                val columns = line.split('\t', limit = 8)
                if (columns.size != 8) return@forEach
                val wordKey = columns[1].lowercase()
                val replacement = editorial[wordKey]
                if (replacement != null) {
                    if (covered.add(wordKey)) result += replacement
                } else {
                    val phonetic = columns[2].trim().let { value ->
                        if (value.isBlank() || value.startsWith('/')) value else "/$value/"
                    }
                    result += VocabularyCard(
                        id = columns[0],
                        word = columns[1],
                        phonetic = phonetic,
                        partOfSpeech = columns[3].ifBlank { "word" },
                        meaningZh = columns[4],
                        meaningZhTw = columns[4],
                        example = "",
                        exampleZh = "",
                        roots = "",
                        collocations = emptyList(),
                        level = columns[5],
                        topic = columns[6]
                    )
                }
            }
        }
        SeedContent.vocabulary.filterNot { it.word.lowercase() in covered }.forEach(result::add)
        result.distinctBy { it.word.lowercase() }.also { catalog ->
            check(catalog.size == EXPECTED_TOTAL) {
                "IELTS vocabulary asset is incomplete: expected $EXPECTED_TOTAL words, found ${catalog.size}"
            }
        }
    }

    private companion object {
        const val DATASET = "datasets/ielts_vocabulary.tsv.bin"
        const val EXPECTED_TOTAL = 5_048
    }
}
