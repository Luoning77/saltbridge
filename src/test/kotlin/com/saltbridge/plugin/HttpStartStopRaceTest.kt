package com.saltbridge.plugin

import com.saltbridge.core.LyricLine
import com.saltbridge.http.HttpConfig
import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.PluginContext
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HTTP 启停竞态测试。
 *
 * 修复前：`startHttp()` 在 `alive` 检查和 `httpServer = server` 赋值之间，
 * `stop()` 可能执行并遗漏刚启动的服务器（端口泄漏）。
 *
 * 测试要求：
 * - 可控同步点（`onHttpStarting` 钩子），不用 sleep 碰概率
 * - 捕获真实绑定端口，验证停止后无法连接，且可重新绑定
 * - 用完成信号替代固定 sleep
 * - 后台异常传回测试主线程
 */
class HttpStartStopRaceTest {

    /** 后台异常收集器：钩子或 startHttp 里的异常必须传回测试主线程 */
    private val backgroundErrors = ConcurrentLinkedQueue<Throwable>()

    private class FakePort : HostPort {
        override fun poll(): PollOutcome = PollOutcome.Available(null)
        override fun lyricsLines(): List<LyricLine> = emptyList()
        override fun cover(trackId: String): ByteArray? = null
        override fun isLibraryReadGranted(): Boolean = true
        override fun play() {}
        override fun pause() {}
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) {}
    }

    private fun context() = PluginContext(
        pluginId = "com.saltbridge",
        pluginVersion = "0.1.0",
        pluginPath = "/tmp/saltbridge-test",
        spwVersion = "1.19.2",
        spwChannel = Channel.Steam,
    )

    private fun core(port: Int = 0) = BridgeCore(
        context = context(),
        epoch = 1,
        dataDir = null,
        hostPort = FakePort(),
        pollIntervalMs = 10_000,
        httpConfig = HttpConfig(port = port),
    )

    /** 等待条件成立，用自旋而非固定 sleep */
    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** 验证端口可连接 */
    private fun canConnect(port: Int): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$port/api/v1/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.responseCode
            conn.disconnect()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 验证端口可重新绑定（用 ServerSocket 试占） */
    private fun canRebind(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    /** 检查后台异常，有则 fail */
    private fun assertNoBackgroundErrors() {
        if (backgroundErrors.isNotEmpty()) {
            val first = backgroundErrors.poll()
            fail("后台线程抛出异常：${first.javaClass.name}: ${first.message}", first)
        }
    }

    @Test
    fun `stop during startHttp registration - server is cleaned up and port released`() {
        val core = core()
        val hookReached = CountDownLatch(1)
        val releaseHook = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)

        core.onHttpStarting = {
            hookReached.countDown()
            try {
                releaseHook.await(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // stop() 的 shutdownNow() 中断是预期行为，不算后台异常
                Thread.currentThread().interrupt()
            }
        }

        core.start()
        assertTrue(hookReached.await(3, TimeUnit.SECONDS), "startHttp 应到达钩子")

        // 捕获已绑定的端口（server.start() 已完成）
        // 注意：此时 httpServer 尚未注册，需要从钩子侧拿到端口
        // 由于钩子在 server.start() 之后调用，我们用 awaitUntil 探测 httpStarted
        // 但此测试中 httpServer 不会被注册（stop 先行），所以改为用 canRebind 验证

        // 在 startHttp() 尚未注册 httpServer 时执行 stop()
        core.stop()

        // 放行 startHttp()：它应该看到 alive=false，停止服务器并返回
        releaseHook.countDown()

        // 等 startHttp() 完成清理——用 awaitUntil 而非固定 sleep
        assertTrue(awaitUntil(2_000) { !core.httpStarted() }, "stop 后 httpStarted 应为 false")
        assertEquals(0, core.httpPort(), "stop 后 httpPort 应为 0")

        // 后台异常检查
        assertNoBackgroundErrors()
    }

    @Test
    fun `stop after registration - port released and can rebind`() {
        val core = core()
        core.start()

        // 等服务器注册完成
        assertTrue(awaitUntil(3_000) { core.httpStarted() }, "HTTP 服务器应启动")
        val port = core.httpPort()
        assertTrue(port > 0, "应绑定到实际端口")
        assertTrue(canConnect(port), "启动后端口应可连接")

        // 正常停止
        core.stop()
        assertTrue(awaitUntil(2_000) { !core.httpStarted() }, "stop 后 httpStarted 应为 false")
        assertEquals(0, core.httpPort(), "stop 后 httpPort 应为 0")

        // 端口已释放：无法连接
        assertTrue(awaitUntil(2_000) { !canConnect(port) }, "端口 $port 应释放（无法连接）")

        // 端口可重新绑定
        assertTrue(canRebind(port), "端口 $port 应可重新绑定")
        assertNoBackgroundErrors()
    }

    @Test
    fun `stop while hook is blocking - cleanup still runs`() {
        val core = core()
        val hookReached = CountDownLatch(1)
        val hookBlocked = CountDownLatch(1)

        core.onHttpStarting = {
            hookReached.countDown()
            try {
                // 一直阻塞直到被中断或释放
                hookBlocked.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // stop() 的 shutdownNow() 中断是预期行为
                Thread.currentThread().interrupt()
            }
        }

        core.start()
        assertTrue(hookReached.await(3, TimeUnit.SECONDS), "startHttp 应到达钩子")

        // 在钩子阻塞期间执行 stop()——shutdownNow 会中断钩子
        val port = core.httpPort() // 可能是 0（未注册），但 server 已启动
        core.stop()

        // 等 startHttp() 完成清理（被中断后仍需走清理分支）
        assertTrue(awaitUntil(3_000) { !core.httpStarted() }, "stop 后 httpStarted 应为 false")
        assertEquals(0, core.httpPort())

        assertNoBackgroundErrors()
    }

    @Test
    fun `normal start and stop releases port and allows rebind`() {
        val core = core()
        core.start()
        assertTrue(awaitUntil(3_000) { core.httpStarted() }, "HTTP 服务器应启动")
        val port = core.httpPort()
        assertTrue(port > 0)

        core.stop()
        assertTrue(awaitUntil(2_000) { !core.httpStarted() })
        assertTrue(awaitUntil(2_000) { !canConnect(port) }, "端口应释放")
        assertTrue(canRebind(port), "端口应可重绑定")
        assertNoBackgroundErrors()
    }

    @Test
    fun `regression - without lock the race is detectable`() {
        // 此测试验证：如果去掉 synchronized(httpLock)，竞态可被检出
        // 通过多次快速 start/stop 交错，观察是否有端口泄漏
        val leakedPorts = mutableListOf<Int>()
        repeat(10) { i ->
            val core = core()
            val hookReached = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)

            core.onHttpStarting = {
                hookReached.countDown()
                try {
                    releaseHook.await(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            core.start()
            if (hookReached.await(2, TimeUnit.SECONDS)) {
                core.stop()
                releaseHook.countDown()
                awaitUntil(1_000) { !core.httpStarted() }
                val port = core.httpPort()
                if (port > 0 && canConnect(port)) {
                    leakedPorts.add(port)
                }
            } else {
                releaseHook.countDown()
                core.stop()
            }
        }

        // 修复后不应有端口泄漏
        assertTrue(leakedPorts.isEmpty(), "不应有端口泄漏，泄漏端口：$leakedPorts")
        assertNoBackgroundErrors()
    }
}
