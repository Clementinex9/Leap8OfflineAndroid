package com.leap8.offline.ai

import com.leap8.offline.core.Leap8CoreNative
import java.io.File

/** Bounded metadata probe only; never maps tensor data or trusts the filename. */
internal fun ggufArchitecture(file: File?): String? = runCatching {
    if (file == null || !file.isFile || !file.canRead()) return@runCatching null
    Leap8CoreNative.readGgufArchitecture(file.absolutePath)
}.getOrNull()

internal fun requiresModernNpuRuntime(architecture: String?) = architecture == "qwen35"
