package com.leap8.offline.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {
    @Test fun defaultVoiceUsesTheOfficialHeartSpeakerId() {
        assertEquals(3, VoiceCatalog.resolve(VoiceCatalog.DEFAULT_VOICE_ID).speakerId)
        assertEquals("af_heart", VoiceCatalog.resolve("unknown").id)
        assertEquals("af_heart", VoiceCatalog.resolve(null).id)
    }

    @Test fun onlyBundledEnglishSpeakersAreExposed() {
        assertEquals(8, VoiceCatalog.voices.size)
        assertEquals(8, VoiceCatalog.voices.map { it.id }.distinct().size)
        assertTrue(VoiceCatalog.voices.all { it.speakerId in 0..27 })
        assertTrue(VoiceCatalog.resolve("bf_emma").british)
        assertEquals(26, VoiceCatalog.resolve("bm_george").speakerId)
    }

    @Test fun speedIsFiniteBoundedAndSlowModeIsApplied() {
        assertEquals(1f, VoiceCatalog.normalizeSpeed(Float.NaN), .001f)
        assertEquals(1f, VoiceCatalog.normalizeSpeed(Float.POSITIVE_INFINITY), .001f)
        assertEquals(.7f, VoiceCatalog.normalizeSpeed(-1f), .001f)
        assertEquals(1.3f, VoiceCatalog.normalizeSpeed(5f), .001f)
        assertEquals(.82f, VoiceCatalog.effectiveSpeed(1f, true), .001f)
        assertEquals(.6f, VoiceCatalog.effectiveSpeed(.7f, true), .001f)
    }

    @Test fun phonemizerUsesCanonicalVoiceNamesInsteadOfBritishLanguageAlias() {
        assertTrue(VoiceCatalog.voices.filter { it.british }.all { it.phonemizerVoice == "en" })
        assertTrue(VoiceCatalog.voices.filterNot { it.british }.all { it.phonemizerVoice == "en-us" })
        assertTrue(VoiceCatalog.voices.none { it.phonemizerVoice == "en-gb" })
    }
}
