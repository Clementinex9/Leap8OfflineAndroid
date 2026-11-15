package com.leap8.offline.data

import android.content.Context
import com.leap8.offline.core.Leap8CoreNative
import com.leap8.offline.domain.VocabularyCard

/** Every IELTS-tagged ECDICT entry, enriched by the 30 editorial SeedContent cards. */
class VocabularyCatalog(context: Context) {
    private val appContext = context.applicationContext

    val cards: List<VocabularyCard> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val gzipBytes = appContext.assets.open(DATASET).use { it.readBytes() }
        val editorial = SeedContent.vocabulary.map { card ->
            Leap8CoreNative.NativeVocabCard(
                id = card.id,
                word = card.word,
                phonetic = card.phonetic,
                partOfSpeech = card.partOfSpeech,
                meaningZh = card.meaningZh,
                meaningZhTw = card.meaningZhTw,
                example = card.example,
                exampleZh = card.exampleZh,
                roots = card.roots,
                collocations = card.collocations,
                level = card.level,
                topic = card.topic
            )
        }
        Leap8CoreNative.loadVocabularyCatalog(
            gzipBytes = gzipBytes,
            editorialCards = editorial,
            sortByLevelWord = true
        ).map { nativeCard ->
            VocabularyCard(
                id = nativeCard.id,
                word = nativeCard.word,
                phonetic = nativeCard.phonetic,
                partOfSpeech = nativeCard.partOfSpeech,
                meaningZh = nativeCard.meaningZh,
                meaningZhTw = nativeCard.meaningZhTw,
                example = nativeCard.example,
                exampleZh = nativeCard.exampleZh,
                roots = nativeCard.roots,
                collocations = nativeCard.collocations,
                level = nativeCard.level,
                topic = nativeCard.topic
            )
        }
    }

    private companion object {
        const val DATASET = "datasets/ielts_vocabulary.tsv.bin"
    }
}
