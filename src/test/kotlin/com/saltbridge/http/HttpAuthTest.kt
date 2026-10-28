package com.saltbridge.http

import com.saltbridge.core.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpAuthTest {

    private val port = 8737
    private val token = "sb_" + "a".repeat(43)

    @Test
    fun `accepts loopback hosts`() {
        listOf("127.0.0.1:8737", "localhost:8737", "[::1]:8737").forEach { host ->
            assertNull(AuthGuard.checkHost(mapOf("host" to host), port), "应接受 $host")
        }
        assertNull(AuthGuard.checkHost(mapOf("host" to "LOCALHOST:8737"), port))
    }

    @Test
    fun `rejects missing or foreign host`() {
        assertEquals("forbidden_host", AuthGuard.checkHost(emptyMap(), port)?.code)
        assertEquals("forbidden_host", AuthGuard.checkHost(mapOf("host" to "evil.example:8737"), port)?.code)
        assertEquals("forbidden_host", AuthGuard.checkHost(mapOf("host" to "127.0.0.1:9999"), port)?.code)
    }

    @Test
    fun `rejects any origin header`() {
        assertEquals("forbidden_origin", AuthGuard.checkOrigin(mapOf("origin" to "http://evil"))?.code)
        assertEquals("forbidden_origin", AuthGuard.checkOrigin(mapOf("origin" to "null"))?.code)
        assertNull(AuthGuard.checkOrigin(emptyMap()))
    }

    @Test
    fun `token must be bearer and match`() {
        assertNull(AuthGuard.checkToken(mapOf("authorization" to "Bearer $token"), token))
        assertNull(AuthGuard.checkToken(mapOf("authorization" to "bearer $token"), token))

        assertEquals("unauthorized", AuthGuard.checkToken(emptyMap(), token)?.code)
        assertEquals("unauthorized", AuthGuard.checkToken(mapOf("authorization" to token), token)?.code)
        assertEquals(
            "unauthorized",
            AuthGuard.checkToken(mapOf("authorization" to "Bearer wrong"), token)?.code,
        )
    }

    @Test
    fun `constant time compare handles different lengths`() {
        assertTrue(AuthGuard.constantTimeEquals("abc", "abc"))
        assertFalse(AuthGuard.constantTimeEquals("abc", "abcd"))
        assertFalse(AuthGuard.constantTimeEquals("", "a"))
        assertTrue(AuthGuard.constantTimeEquals("", ""))
    }

    @Test
    fun `generates a well formed token once`() {
        val store = MemoryTokenStore()
        val provider = TokenProvider(store)

        val first = provider.current()

        assertTrue(first.startsWith("sb_"))
        assertEquals(46, first.length)
        assertEquals(first, provider.current(), "同一实例内必须稳定")
        assertEquals(first, store.load(), "首次生成必须落盘")
    }

    @Test
    fun `reuses a well formed stored token`() {
        val existing = "sb_" + "A".repeat(43)
        val provider = TokenProvider(MemoryTokenStore(existing))
        assertEquals(existing, provider.current())
    }

    @Test
    fun `replaces a malformed stored token`() {
        val provider = TokenProvider(MemoryTokenStore("broken"))
        val created = provider.current()
        assertTrue(created.startsWith("sb_"))
        assertNotEquals("broken", created)
    }

    @Test
    fun `reset changes token and persists`() {
        val store = MemoryTokenStore()
        val provider = TokenProvider(store)

        val first = provider.current()
        val second = provider.reset()

        assertNotEquals(first, second)
        assertEquals(second, store.load())
        assertEquals(second, provider.current())
    }

    @Test
    fun `rate limiter rejects beyond the window`() {
        val clock = FakeClock()
        val limiter = RateLimiter(2, clock)

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())

        clock.advanceBy(1_000)
        assertTrue(limiter.tryAcquire())
    }
}
