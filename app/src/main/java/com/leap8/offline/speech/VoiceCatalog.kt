package com.leap8.offline.speech

/** Stable names and the exact speaker IDs in sherpa's Kokoro v1.0 release. */
data class NaturalVoiceOption(
    val id: String,
    val label: String,
    val speakerId: Int,
    val british: Boolean = false
) {
    // sherpa 1.13.2's piper frontend uses espeak_SetVoiceByName, not a BCP-47
    // language lookup. The British voice file is gmw/en; "en-gb" is only a
    // language alias and aborts that native frontend with VOICE_NOT_FOUND.
    val phonemizerVoice: String get() = if (british) "en" else "en-us"
}

object VoiceCatalog {
    const val DEFAULT_VOICE_ID = "af_heart"
    const val DEFAULT_SPEED = 1f
    val voices: List<NaturalVoiceOption> = listOf(
        NaturalVoiceOption("af_heart", "Heart · 美式女声", 3),
        NaturalVoiceOption("af_bella", "Bella · 美式女声", 2),
        NaturalVoiceOption("af_nicole", "Nicole · 美式女声", 6),
        NaturalVoiceOption("am_michael", "Michael · 美式男声", 16),
        NaturalVoiceOption("am_fenrir", "Fenrir · 美式男声", 14),
        NaturalVoiceOption("bf_emma", "Emma · 英式女声", 21, british = true),
        NaturalVoiceOption("bf_isabella", "Isabella · 英式女声", 22, british = true),
        NaturalVoiceOption("bm_george", "George · 英式男声", 26, british = true)
    )

    fun resolve(id: String?): NaturalVoiceOption =
        voices.firstOrNull { it.id == id } ?: voices.first()

    fun normalizeSpeed(speed: Float): Float =
        if (speed.isFinite()) speed.coerceIn(.7f, 1.3f) else DEFAULT_SPEED

    fun effectiveSpeed(speed: Float, slower: Boolean): Float =
        (normalizeSpeed(speed) * if (slower) .82f else 1f).coerceIn(.6f, 1.3f)
}
