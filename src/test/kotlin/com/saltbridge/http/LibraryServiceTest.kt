package com.saltbridge.http

import com.saltbridge.core.FakeClock
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.*

class LibraryServiceTest {
    private fun song(id: Int) = LibraryTrack("$id", "Song $id", "Artist", "Album", null)

    @Test fun `pagination uses an extra row and caches repeated requests`() {
        val clock = FakeClock()
        var calls = 0
        val service = LibraryService(clock, Executor { it.run() }) { after, count ->
            assertEquals(null, after)
            assertEquals(3, count)
            calls++
            listOf(song(1), song(2), song(3))
        }
        assertEquals(LibraryResult.Pending, service.get(null, 2))
        val result = service.get(null, 2) as LibraryResult.Ready
        assertEquals(listOf("1", "2"), result.page.tracks.map { it.id })
        assertEquals("2", result.page.nextCursor)
        assertEquals(1, calls)
        clock.advanceBy(10_001)
        service.get(null, 2)
        assertEquals(2, calls)
    }

    @Test fun `empty library is a completed page`() {
        val service = LibraryService(FakeClock(), Executor { it.run() }) { _, _ -> emptyList() }
        service.get(null, 50)
        val result = service.get(null, 50) as LibraryResult.Ready
        assertTrue(result.page.tracks.isEmpty())
        assertNull(result.page.nextCursor)
    }

    @Test fun `duplicate requests and pending work are bounded and clear invalidates tasks`() {
        val tasks = mutableListOf<Runnable>()
        var calls = 0
        val service = LibraryService(FakeClock(), Executor { tasks += it }) { _, _ -> calls++; emptyList() }
        repeat(100) { service.get("$it", 50) }
        assertEquals(4, tasks.size)
        service.get("0", 50)
        assertEquals(4, tasks.size)
        service.clear()
        tasks.forEach { it.run() }
        assertEquals(0, calls)
    }

    @Test fun `rejected submission can be retried`() {
        var submitted = 0
        val service = LibraryService(FakeClock(), Executor {
            if (++submitted == 1) throw RejectedExecutionException()
            it.run()
        }) { _, _ -> emptyList() }
        service.get(null, 50)
        service.get(null, 50)
        assertTrue(service.get(null, 50) is LibraryResult.Ready)
    }
}
