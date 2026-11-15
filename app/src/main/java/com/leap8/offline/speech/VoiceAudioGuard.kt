package com.leap8.offline.speech

import com.leap8.offline.core.Leap8CoreNative

/** Fail safely and apply studio voice mastering (de-ess, anti-hiss LPF, soft limiter) in C++20. */
internal object VoiceAudioGuard {
    fun validate(samples: FloatArray) {
        Leap8CoreNative.validateAudioPcm(samples)
    }

    fun validateAndEnhance(samples: FloatArray, sampleRate: Int = 24_000) {
        Leap8CoreNative.enhanceSpeechPcm(samples, sampleRate)
    }
}
