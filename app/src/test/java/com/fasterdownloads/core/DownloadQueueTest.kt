package com.fasterdownloads.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadQueueTest {

    @Before
    fun reset() {
        DownloadController.reset()
    }

    @Test
    fun addingADownloadQueuesItAndKeepsItsRequest() {
        val id = DownloadController.add(DownloadRequest(url = "https://example.com/a.bin"))
        val state = DownloadController.state(id)

        assertNotNull(state)
        assertEquals(DownloadPhase.QUEUED, state!!.phase)
        assertEquals("https://example.com/a.bin", state.url)
        // The request keeps the job id, so an engine can tag every snapshot it emits.
        assertEquals(id, DownloadController.request(id)?.id)
    }

    @Test
    fun severalDownloadsLiveSideBySideNewestFirst() {
        val first = DownloadController.add(DownloadRequest(url = "https://example.com/1.bin"))
        val second = DownloadController.add(DownloadRequest(url = "https://example.com/2.bin"))

        val list = DownloadController.downloads.value
        assertEquals(2, list.size)
        assertEquals(second, list.first().id)
        assertEquals(first, list.last().id)
    }

    @Test
    fun updatesReplaceTheMatchingJobOnly() {
        val first = DownloadController.add(DownloadRequest(url = "https://example.com/1.bin"))
        val second = DownloadController.add(DownloadRequest(url = "https://example.com/2.bin"))

        val running = DownloadController.state(first)!!.copy(phase = DownloadPhase.DOWNLOADING, doneBytes = 10L)
        DownloadController.update(running)

        assertEquals(DownloadPhase.DOWNLOADING, DownloadController.state(first)!!.phase)
        assertEquals(10L, DownloadController.state(first)!!.doneBytes)
        assertEquals(DownloadPhase.QUEUED, DownloadController.state(second)!!.phase)
        assertEquals(2, DownloadController.downloads.value.size)
    }

    @Test
    fun removeForgetsOneJobAndClearFinishedForgetsTheRest() {
        val keep = DownloadController.add(DownloadRequest(url = "https://example.com/keep.bin"))
        val dropped = DownloadController.add(DownloadRequest(url = "https://example.com/drop.bin"))
        DownloadController.update(
            DownloadController.state(dropped)!!.copy(phase = DownloadPhase.COMPLETED),
        )

        DownloadController.clearFinished()
        assertNull(DownloadController.state(dropped))
        assertNotNull(DownloadController.state(keep))

        DownloadController.remove(keep)
        assertNull(DownloadController.state(keep))
        assertNull(DownloadController.request(keep))
        assertTrue(DownloadController.downloads.value.isEmpty())
    }

    @Test
    fun phasesDecideWhichButtonsMakeSense() {
        val queued = DownloadUiState(phase = DownloadPhase.QUEUED)
        assertTrue(queued.running)
        assertTrue(!queued.terminal)

        val downloading = DownloadUiState(phase = DownloadPhase.DOWNLOADING)
        assertTrue(downloading.pausable)
        assertTrue(!downloading.resumable)

        val paused = DownloadUiState(phase = DownloadPhase.PAUSED)
        assertTrue(!paused.running)
        assertTrue(paused.resumable)

        val done = DownloadUiState(phase = DownloadPhase.COMPLETED)
        assertTrue(done.terminal)
        assertTrue(!done.pausable)
    }
}
