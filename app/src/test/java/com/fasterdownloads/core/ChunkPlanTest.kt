package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlanTest {

    @Test
    fun chunkSizeStaysWithinBounds() {
        val size = ChunkPlan.chunkSize(100L * 1024 * 1024, workers = 8)
        assertTrue(size >= ChunkPlan.MIN_CHUNK)
        assertTrue(size <= ChunkPlan.MAX_CHUNK)
    }

    @Test
    fun chunkSizeGrowsForHugeFiles() {
        val size = ChunkPlan.chunkSize(200L * 1024 * 1024 * 1024, workers = 8)
        assertTrue(size <= ChunkPlan.MAX_CHUNK)
        val count = ChunkPlan.chunkCount(200L * 1024 * 1024 * 1024, size)
        assertTrue("chunk count $count should stay manageable", count <= 100_000)
    }

    @Test
    fun chunksTileTheFileExactly() {
        val total = 37_123_457L
        val size = ChunkPlan.chunkSize(total, workers = 4)
        val count = ChunkPlan.chunkCount(total, size)
        var covered = 0L
        var expectedStart = 0L
        for (index in 0 until count) {
            val range = ChunkPlan.bounds(total, size, index)
            assertNotNull(range)
            range!!
            assertEquals(expectedStart, range.first)
            covered += range.last - range.first + 1
            expectedStart = range.last + 1
        }
        assertEquals(total, covered)
        assertEquals(total - 1, ChunkPlan.bounds(total, size, count - 1)!!.last)
    }

    @Test
    fun boundsAreExclusiveAtTheEnd() {
        val total = 1000L
        assertNull(ChunkPlan.bounds(total, 400L, 3))
        assertEquals(800L..999L, ChunkPlan.bounds(total, 400L, 2))
        assertNull(ChunkPlan.bounds(total, 400L, -1))
    }

    @Test
    fun smallFilesStillProduceOneChunk() {
        val total = 512L
        val size = ChunkPlan.chunkSize(total, workers = 8)
        assertEquals(1, ChunkPlan.chunkCount(total, size))
        assertEquals(0L..511L, ChunkPlan.bounds(total, size, 0))
    }

    @Test
    fun workerSplitFollowsTheRequestedWeight() {
        assertEquals(6 to 2, ChunkPlan.workerCounts(totalWorkers = 8, wifiSharePercent = 70))
        assertEquals(4 to 4, ChunkPlan.workerCounts(totalWorkers = 8, wifiSharePercent = 50))
        assertEquals(7 to 1, ChunkPlan.workerCounts(totalWorkers = 8, wifiSharePercent = 90))
    }

    @Test
    fun bothRadiosAlwaysGetAWorker() {
        val (wifi, mobile) = ChunkPlan.workerCounts(totalWorkers = 2, wifiSharePercent = 90)
        assertTrue(wifi >= 1)
        assertTrue(mobile >= 1)
    }

    @Test
    fun zeroSizedInputsAreSafe() {
        assertEquals(0, ChunkPlan.chunkCount(0L, 100L))
        assertNull(ChunkPlan.bounds(0L, 100L, 0))
    }
}
