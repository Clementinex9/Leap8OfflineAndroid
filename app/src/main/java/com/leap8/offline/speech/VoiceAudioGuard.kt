package com.leap8.offline.speech

import kotlin.math.abs

/** Fail safely instead of playing the loud rail-pinned output seen in older ARM int8 exports. */
internal object VoiceAudioGuard {
    fun validate(samples: FloatArray) {
        check(samples.isNotEmpty()) { "神经语音为空" }
        var peak = 0f
        var clipped = 0
        for (sample in samples) {
            check(sample.isFinite()) { "神经语音包含无效音频" }
            val amplitude = abs(sample)
            if (amplitude > peak) peak = amplitude
            if (amplitude >= .999f) clipped++
        }
        check(peak > .00001f) { "神经语音没有可听音频" }
        check(clipped < samples.size * .2f) { "神经语音异常削波，已阻止播放" }
    }
}
