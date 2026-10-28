@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package com.saltbridge.plugin

import com.xuncorp.spw.workshop.api.Channel
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.Proxy
import java.nio.file.Files
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsyncStartupTest {
    @Test fun `slow configuration does not block start and stop cancels pending startup`() {
        val slot = Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi\$Companion").getField("instance")
        val previous = slot.get(null)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val initThread = AtomicReference<Thread>()
        val caller = Executors.newSingleThreadExecutor()
        val dir = Files.createTempDirectory("saltbridge-startup")
        val helper = Proxy.newProxyInstance(ConfigHelper::class.java.classLoader, arrayOf(ConfigHelper::class.java)) { _, method, args ->
            when (method.name) {
                "getConfigPath" -> dir.resolve("config.json")
                "get" -> args[1]
                "save", "reload" -> true
                else -> null
            }
        }
        val config = Proxy.newProxyInstance(ConfigManager::class.java.classLoader, arrayOf(ConfigManager::class.java)) { _, _, _ -> helper }
        val manager = Proxy.newProxyInstance(WorkshopApi.Manager::class.java.classLoader, arrayOf(WorkshopApi.Manager::class.java)) { _, _, _ -> config }
        val fake = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi::class.java)) { _, _, _ ->
            initThread.set(Thread.currentThread())
            entered.countDown()
            var waiting = true
            while (waiting) {
                try { release.await(); waiting = false } catch (_: InterruptedException) { /* 模拟不能立即中断的 IO */ }
            }
            manager // 慢读取最终成功，必须仍被停用代次校验拦截。
        }
        try {
            slot.set(null, fake)
            val result = caller.submit<Boolean> {
                CoreRegistry.start(PluginContext("com.saltbridge", "0.1.0", "/tmp/test", "1.19.2", Channel.Steam))
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(result.get(1, TimeUnit.SECONDS), "配置读取未结束，start 也应已经返回")
            CoreRegistry.stop()
            release.countDown()
            initThread.get().join(2000)
            assertTrue(!initThread.get().isAlive)
            assertNull(CoreRegistry.current(), "停用后迟到的初始化不得发布运行实例")
        } finally {
            release.countDown()
            CoreRegistry.stop()
            caller.shutdownNow()
            initThread.get()?.join(2000)
            slot.set(null, previous)
            Files.deleteIfExists(dir)
        }
    }
}
