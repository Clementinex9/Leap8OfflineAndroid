package com.leap8.offline.speech

import org.junit.Test

class VoiceAudioGuardTest {
    @Test fun allowsNormalSpeechWithSilence() {
        VoiceAudioGuard.validate(floatArrayOf(0f, .02f, -.3f, .2f, 0f))
    }
    @Test(expected = IllegalStateException::class)
    fun rejectsNaNAudio() { VoiceAudioGuard.validate(floatArrayOf(Float.NaN)) }
    @Test(expected = IllegalStateException::class)
    fun rejectsSilentAudio() { VoiceAudioGuard.validate(FloatArray(100)) }
    @Test(expected = IllegalStateException::class)
    fun rejectsRailPinnedAudio() { VoiceAudioGuard.validate(FloatArray(100) { 1f }) }
}
