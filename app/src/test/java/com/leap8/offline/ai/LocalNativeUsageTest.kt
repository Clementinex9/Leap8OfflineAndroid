package com.leap8.offline.ai

import org.junit.Assert.*
import org.junit.Test

class LocalNativeUsageTest {
    @Test fun validatesBothIndependentBridgePayloadsWithoutTextEstimates() {
        assertEquals(LocalNativeUsage(74, 128, 201, 6001), parseLocalNativeUsage(longArrayOf(1, 74, 128, 201, 6001)))
        // Zero output tokens is a valid measurement, unlike absent statistics.
        assertEquals(0, parseLocalNativeUsage(longArrayOf(1, 1, 0, 0, 0))?.outputTokens)
    }

    @Test fun missingTruncatedUnknownVersionAndNegativeValuesStayUnknown() {
        assertNull(parseLocalNativeUsage(null))
        assertNull(parseLocalNativeUsage(longArrayOf()))
        assertNull(parseLocalNativeUsage(longArrayOf(1, 20, 4, 0)))
        assertNull(parseLocalNativeUsage(longArrayOf(2, 20, 4, 0, 1)))
        for (index in 1..4) {
            val values = longArrayOf(1, 20, 4, 0, 1)
            values[index] = -1
            assertNull(parseLocalNativeUsage(values))
        }
    }

    @Test fun neverWrapsOversizedTokenCountersIntoNegativeInts() {
        assertNull(parseLocalNativeUsage(longArrayOf(1, Int.MAX_VALUE.toLong() + 1, 4, 0, 1)))
        assertNull(parseLocalNativeUsage(longArrayOf(1, 20, Int.MAX_VALUE.toLong() + 1, 0, 1)))
    }
}
