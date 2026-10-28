package com.saltbridge.http

import com.saltbridge.core.FakeClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverServiceTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01,
    )

    private val directExecutor = Executor { it.run() }

    @Test
    fun `first request is pending then ready`() {
        val calls = AtomicInteger()
        val service = CoverService(HttpConfig(), FakeClock(), directExecutor) {
            calls.incrementAndGet()
            jpeg
        }

        assertEquals(CoverResult.Pending, service.get(1, "track-1"))
        assertEquals(1, calls.get())

        val ready = service.get(1, "track-1")
        assertTrue(ready is CoverResult.Ready)
        assertEquals("image/jpeg", ready.contentType)
        assertEquals(jpeg.size, ready.bytes.size)
        assertEquals(1, calls.get(), "命中缓存不得再次调用宿主")
    }

    @Test
    fun `concurrent requests share one load`() {
        val calls = AtomicInteger()
        val gate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val service = CoverService(HttpConfig(), FakeClock(), executor) {
            calls.incrementAndGet()
            gate.await()
            png
        }

        assertEquals(CoverResult.Pending, service.get(1, "track-1"))
        assertEquals(CoverResult.Pending, service.get(1, "track-1"), "同一键在飞时不得重复加载")

        gate.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, calls.get())

        val ready = service.get(1, "track-1")
        assertTrue(ready is CoverResult.Ready)
        assertEquals("image/png", ready.contentType)
    }

    @Test
    fun `failure is cached for the ttl`() {
        val clock = FakeClock()
        val calls = AtomicInteger()
        val service = CoverService(
            HttpConfig(coverFailureTtlMs = 30_000),
            clock,
            directExecutor,
        ) {
            calls.incrementAndGet()
            null
        }

        assertEquals(CoverResult.Pending, service.get(1, "track-1"))
        assertEquals(CoverResult.Unavailable, service.get(1, "track-1"))
        assertEquals(1, calls.get())

        clock.advanceBy(29_999)
        assertEquals(CoverResult.Unavailable, service.get(1, "track-1"))
        assertEquals(1, calls.get(), "TTL 内不得重试")

        clock.advanceBy(2)
        assertEquals(CoverResult.Pending, service.get(1, "track-1"), "TTL 过后应重新尝试")
        assertEquals(2, calls.get())
    }

    @Test
    fun `oversized cover is unavailable`() {
        val service = CoverService(HttpConfig(coverMaxBytes = 4), FakeClock(), directExecutor) {
            ByteArray(5) { 1 }
        }

        assertEquals(CoverResult.Pending, service.get(1, "track-1"))
        assertEquals(CoverResult.Unavailable, service.get(1, "track-1"))
    }

    @Test
    fun `generation is part of the cache key`() {
        val calls = AtomicInteger()
        val service = CoverService(HttpConfig(), FakeClock(), directExecutor) {
            calls.incrementAndGet()
            jpeg
        }

        assertEquals(CoverResult.Pending, service.get(1, "track-1"))
        assertEquals(CoverResult.Pending, service.get(2, "track-1"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `clear drops cache and failures`() {
        val calls = AtomicInteger()
        val service = CoverService(HttpConfig(), FakeClock(), directExecutor) {
            calls.incrementAndGet()
            null
        }

        assertEquals(CoverResult.Pending, service.get(1, "track-1"))
        assertEquals(CoverResult.Unavailable, service.get(1, "track-1"))

        service.clear()
        assertEquals(CoverResult.Pending, service.get(1, "track-1"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `sniffs common image types`() {
        assertEquals("image/jpeg", CoverSniffer.contentType(jpeg))
        assertEquals("image/png", CoverSniffer.contentType(png))
        assertEquals("image/gif", CoverSniffer.contentType("GIF89a".toByteArray()))
        assertEquals(
            "image/webp",
            CoverSniffer.contentType("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()),
        )
        assertEquals("application/octet-stream", CoverSniffer.contentType(byteArrayOf(1, 2, 3)))
    }
}
