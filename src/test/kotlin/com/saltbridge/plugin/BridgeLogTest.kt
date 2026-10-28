package com.saltbridge.plugin

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class BridgeLogTest {
    @Test fun `close drains accepted messages on the background writer`() {
        val dir = Files.createTempDirectory("saltbridge-log-test")
        val path = dir.resolve("bridge.log")
        val log = BridgeLog(path)
        repeat(10) { log.line("test", "entry-$it") }
        log.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var complete = false
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) && Files.readString(path).contains("entry-9")) {
                complete = true
                break
            }
            Thread.sleep(5)
        }
        assertTrue(complete, "关闭后应由后台线程刷新已接受的日志")
        Files.deleteIfExists(path)
        Files.deleteIfExists(dir)
    }

    @Test fun `slow or invalid log destinations do not fail callers`() {
        val dir = Files.createTempDirectory("saltbridge-invalid-log")
        val log = BridgeLog(dir) // 目录不能作为日志文件打开。
        repeat(1000) { log.line("test", "entry-$it") }
        log.close()
        Files.deleteIfExists(dir)
    }
}
