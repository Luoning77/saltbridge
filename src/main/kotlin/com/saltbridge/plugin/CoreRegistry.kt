@file:OptIn(UnstableSpwWorkshopApi::class)

package com.saltbridge.plugin

import com.google.gson.GsonBuilder
import com.saltbridge.http.HttpConfig
import com.saltbridge.http.MemoryTokenStore
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 当前启用周期的唯一入口。
 *
 * `stop()` 先摘除引用再关闭实例：宿主若在停用后仍投递旧回调，
 * `current()` 直接返回 null（R1 的可用缓解，不承诺完全解决）。
 */
object CoreRegistry {

    private val ref = AtomicReference<BridgeCore?>(null)
    private val epochCounter = AtomicLong(0)

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun current(): BridgeCore? = ref.get()?.takeIf { it.isActive }

    fun start(context: PluginContext): Boolean {
        if (ref.get()?.isActive == true) return true

        val dir = runCatching { dataDir() }.getOrNull() ?: return false
        val tokenStore = runCatching {
            ConfigTokenStore(WorkshopApi.manager.createConfigManager().getConfig())
        }.getOrNull() ?: MemoryTokenStore()

        val (httpConfig, pollIntervalMs) = loadRuntimeConfig()

        val core = BridgeCore(
            context = context,
            epoch = epochCounter.incrementAndGet(),
            dataDir = dir,
            tokenStore = tokenStore,
            pollIntervalMs = pollIntervalMs,
            httpConfig = httpConfig,
        )
        ref.set(core)
        core.start()
        return true
    }

    /**
     * 从 `config.json` 读运行参数。
     *
     * 配置界面没有数字输入控件（M0 已记录），所以这两个值只能手动改文件；
     * 取值非法时静默回退默认，避免插件因此起不来。
     */
    private fun loadRuntimeConfig(): Pair<HttpConfig, Long> {
        val base = HttpConfig()
        var port = base.port
        var pollMs = DEFAULT_POLL_MS

        val helper = runCatching { WorkshopApi.manager.createConfigManager().getConfig() }.getOrNull()
        if (helper != null) {
            runCatching { helper.get<Any>("http.port", base.port) }.getOrNull()?.let { value ->
                (value as? Number)?.toInt()?.takeIf { it in 1..65_535 }?.let { port = it }
            }
            runCatching { helper.get<Any>("poll.intervalMs", DEFAULT_POLL_MS) }.getOrNull()?.let { value ->
                (value as? Number)?.toLong()?.takeIf { it in 250..60_000 }?.let { pollMs = it }
            }
        }

        return base.copy(port = port) to pollMs
    }

    fun stop() {
        val core = ref.getAndSet(null) ?: return
        core.stop()
    }

    fun onUpdate() {
        current()?.onUpdate()
    }

    fun onDelete() {
        val core = ref.getAndSet(null) ?: return
        core.onDelete()
        core.stop()
    }

    fun logMarker() {
        current()?.logMarker()
    }

    /** 把当前快照按协议 5.3 的字段形状写到 `reports/state-snapshot.json`。 */
    fun exportSnapshot(): Path? {
        val core = current() ?: return null
        val status = core.snapshotJson() ?: return null

        val dir = runCatching { dataDir() }.getOrNull() ?: return null
        val file = dir.resolve("reports").resolve("state-snapshot.json")
        return runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(status))
            file
        }.getOrNull()
    }

    /**
     * 把令牌写到 `token.txt`。
     *
     * toast 可能被宿主截断，**文件是唯一可靠的读取方式**（协议 2.2）。
     */
    fun exportToken(): Path? {
        val core = current() ?: return null
        val dir = runCatching { dataDir() }.getOrNull() ?: return null
        val file = dir.resolve("token.txt")
        return runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, core.token() + "\n")
            file
        }.getOrNull()
    }

    fun resetToken(): String? = current()?.resetToken()

    fun httpPort(): Int = current()?.httpPort() ?: 0

    fun httpStarted(): Boolean = current()?.httpStarted() == true

    private const val DEFAULT_POLL_MS = 2_000L

    private fun dataDir(): Path {
        val configPath = WorkshopApi.manager.createConfigManager().getConfig().getConfigPath()
        val parent = configPath.parent ?: error("config path has no parent")
        Files.createDirectories(parent)
        return parent
    }
}
