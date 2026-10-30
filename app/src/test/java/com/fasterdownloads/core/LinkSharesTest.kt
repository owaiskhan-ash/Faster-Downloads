package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSharesTest {

    @Test
    fun splitsTwoRadiosBySpeed() {
        assertEquals(listOf(90, 10), linkShares(listOf(900L, 100L)))
        assertEquals(listOf(50, 50), linkShares(listOf(1024L, 1024L)))
        assertEquals(listOf(0, 100), linkShares(listOf(0L, 5000L)))
    }

    @Test
    fun sharesAlwaysAddUpToOneHundred() {
        listOf(
            listOf(1L, 1L, 1L),
            listOf(333L, 333L, 334L),
            listOf(7L, 11L, 13L, 17L),
            listOf(1L, 999L),
        ).forEach { values ->
            val shares = linkShares(values)
            assertEquals(values.toString(), 100, shares.sum())
        }
    }

    @Test
    fun anIdlePairReadsAsZero() {
        assertEquals(listOf(0, 0), linkShares(listOf(0L, 0L)))
        assertTrue(linkShares(emptyList()).isEmpty())
    }

    @Test
    fun aNegativeSampleCountsAsIdleInsteadOfBreakingTheSplit() {
        assertEquals(listOf(0, 100), linkShares(listOf(-5L, 40L)))
    }
}
