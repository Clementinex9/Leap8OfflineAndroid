package com.leap8.offline.speech

/** Invalidates queued and in-flight speech without releasing a native engine while it is busy. */
internal class VoiceRequestState {
    private var generation = 0L
    private var closed = false

    @Synchronized
    fun next(): Long? = if (closed) null else ++generation

    @Synchronized
    fun isCurrent(request: Long): Boolean = !closed && request == generation

    @Synchronized
    fun cancel() { generation++ }

    @Synchronized
    fun close() { closed = true; generation++ }

    @Synchronized
    fun isClosed(): Boolean = closed
}
