package com.saltbridge.http

import com.saltbridge.core.FakeClock
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverBudgetTest {
    @Test fun `clear prevents a late loader from restoring the cache`() {
        val tasks = mutableListOf<Runnable>()
        var calls = 0
        val service = CoverService(HttpConfig(), FakeClock(), Executor { tasks += it }) {
            calls++
            byteArrayOf(1)
        }
        service.get(1, "song")
        service.clear()
        tasks.removeAt(0).run()
        service.get(1, "song")
        assertEquals(1, tasks.size, "清理前的任务不得重新填充缓存")
        assertEquals(0, calls, "执行前已过期的任务不应再查询宿主")
    }

    @Test fun `rejected submission does not leave permanent in flight state`() {
        var submissions = 0
        val service = CoverService(HttpConfig(), FakeClock(), Executor {
            if (++submissions == 1) throw RejectedExecutionException()
            it.run()
        }) { byteArrayOf(1) }
        assertEquals(CoverResult.Pending, service.get(1, "song"))
        assertEquals(CoverResult.Pending, service.get(1, "song"))
        assertEquals(2, submissions)
    }
    @Test fun `cache evicts by total bytes as well as entry count`() {
        var calls = 0
        val service = CoverService(HttpConfig(coverCacheMaxBytes = 5), FakeClock(), Executor { it.run() }) {
            calls++
            byteArrayOf(1, 2, 3)
        }
        service.get(1, "a")
        service.get(1, "b")
        assertEquals(CoverResult.Pending, service.get(1, "a"))
        assertEquals(3, calls, "总字节超限时必须淘汰旧封面")
    }

    @Test fun `pending loads have a fixed upper bound`() {
        val tasks = mutableListOf<Runnable>()
        val service = CoverService(HttpConfig(coverPendingEntries = 2), FakeClock(), Executor { tasks += it }) {
            byteArrayOf(1)
        }
        repeat(100) { service.get(it.toLong(), "song-$it") }
        assertEquals(2, tasks.size)
    }

    @Test fun `negative cache is bounded across different songs`() {
        var calls = 0
        val service = CoverService(HttpConfig(coverFailureEntries = 2), FakeClock(), Executor { it.run() }) {
            calls++
            null
        }
        repeat(3) { service.get(it.toLong(), "song-$it") }
        service.get(0, "song-0")
        assertEquals(4, calls, "失败记录不能随切歌无限累积")
    }

}
