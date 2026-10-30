package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMeterTest {

    @Test
    fun emptyMeterReportsNothing() {
        val meter = SpeedMeter()
        assertEquals(0L, meter.rate())
        meter.add(0L, 1000L)
        assertEquals(0L, meter.rate())
    }

    @Test
    fun averagesConstantThroughput() {
        val meter = SpeedMeter(windowMs = 3000L)
        // 1 byte every millisecond = 1000 B/s
        for (t in 0L..3000L step 500L) meter.add(t, t)
        assertEquals(1000L, meter.rate())
    }

    @Test
    fun ignoresSamplesOlderThanTheWindow() {
        val meter = SpeedMeter(windowMs = 3000L)
        meter.add(0L, 0L)
        meter.add(10_000L, 1_000_000L)
        // Only the newest sample is inside the window, so no rate can be derived yet.
        assertEquals(0L, meter.rate())
        meter.add(10_500L, 2_000_000L)
        assertEquals(2_000_000L, meter.rate())
    }

    @Test
    fun rollbackNeverProducesANegativeRate() {
        val meter = SpeedMeter(windowMs = 3000L)
        meter.add(0L, 5_000_000L)
        meter.add(500L, 4_000_000L)
        assertEquals(0L, meter.rate())
    }

    @Test
    fun survivesRingBufferWraparound() {
        val meter = SpeedMeter(windowMs = 3000L, capacity = 8)
        var bytes = 0L
        for (t in 0L..(500L * 40) step 500L) {
            bytes += 100_000L
            meter.add(t, bytes)
        }
        val rate = meter.rate()
        assertTrue("rate should be positive, was $rate", rate > 0L)
        assertEquals(200_000L, rate)
    }

    @Test
    fun resetClearsHistory() {
        val meter = SpeedMeter()
        meter.add(0L, 0L)
        meter.add(500L, 500_000L)
        meter.reset()
        assertEquals(0L, meter.rate())
    }
}
