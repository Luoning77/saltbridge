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

    private val lifecycleLock = Any()
    private var startup: Thread? = null

    /** 启动线程只登记生命周期；配置、文件与令牌初始化全部在后台。 */
    fun start(context: PluginContext): Boolean = synchronized(lifecycleLock) {
        if (ref.get()?.isActive == true || startup?.isAlive == true) return true
        val ticket = epochCounter.incrementAndGet()
        startup = Thread({
            val startedAt = System.nanoTime()
            try {
                val dir = dataDir()
                val tokenStore = runCatching {
                    ConfigTokenStore(WorkshopApi.manager.createConfigManager().getConfig())
                }.getOrNull() ?: MemoryTokenStore()
                val (httpConfig, pollIntervalMs) = loadRuntimeConfig()
                synchronized(lifecycleLock) {
                    if (ticket != epochCounter.get() || Thread.currentThread().isInterrupted) return@Thread
                    val core = BridgeCore(
                        context = context,
                        epoch = ticket,
                        dataDir = dir,
                        tokenStore = tokenStore,
                        pollIntervalMs = pollIntervalMs,
                        httpConfig = httpConfig,
                    )
                    ref.set(core)
                    core.start()
                }
                // 保留自动配对，但不能在宿主启动线程等待令牌和文件写入。
                val core = ref.get()
                if (ticket == epochCounter.get() && core?.epoch == ticket) {
                    val token = core.token()
                    if (ticket == epochCounter.get()) writePairingToken(token)
                    core.logStartupDuration((System.nanoTime() - startedAt) / 1_000_000L)
                }
            } catch (_: Exception) {
                // 初始化失败保持未运行，避免后台异常拖垮宿主。
                if (ticket == epochCounter.get()) {
                    System.err.println("SaltBridge: background initialization failed")
                }
            }
        }, "saltbridge-init").apply { isDaemon = true; start() }
        true
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
        val core = synchronized(lifecycleLock) {
            epochCounter.incrementAndGet()
            startup?.interrupt()
            startup = null
            ref.getAndSet(null)
        }
        core?.stop()
    }

    fun onUpdate() {
        current()?.onUpdate()
    }

    fun onDelete() {
        current()?.onDelete()
        stop()
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
            writePairingToken(core.token())
            file
        }.getOrNull()
    }

    fun resetToken(): String? {
        val token = current()?.resetToken() ?: return null
        writePairingToken(token)
        return token
    }

    /**
     * 配对文件：把令牌同时写一份到用户主目录 `~/.saltbridge/token.txt`。
     * 供本机可信客户端（如 DangoMusic）自动发现，免手动粘贴；
     * 仅本机用户可读写，令牌仍不进日志、不出回环。
     */
    fun writePairingToken(token: String) {
        runCatching {
            val home = System.getProperty("user.home") ?: return
            val pair = Path.of(home, ".saltbridge", "token.txt")
            Files.createDirectories(pair.parent)
            Files.writeString(pair, token + "\n")
        }
    }

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
