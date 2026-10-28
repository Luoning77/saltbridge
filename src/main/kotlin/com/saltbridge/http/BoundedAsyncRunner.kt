package com.saltbridge.http

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * NanoHTTPD 默认**每个请求新建一个线程**，5 个慢客户端就能把线程数拉爆。
 *
 * 这里换成固定 8 线程 + 有界队列 64；队列满时拒绝并计数，由调用方写入日志。
 * 被拒绝的连接来不及响应，会在 socket 超时后断开——这是已知限制，
 * 比“无限建线程”安全得多。
 */
class BoundedAsyncRunner(
    workers: Int,
    queueCapacity: Int,
) : NanoHTTPD.AsyncRunner {

    val rejected = AtomicLong()

    private val executor = ThreadPoolExecutor(
        workers,
        workers,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        ThreadFactory { runnable ->
            Thread(runnable, "saltbridge-http").apply { isDaemon = true }
        },
        { _, _ -> rejected.incrementAndGet() },
    )

    override fun closeAll() {
        executor.shutdownNow()
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        // 不复用连接，无需回收
    }

    override fun exec(code: NanoHTTPD.ClientHandler) {
        try {
            executor.execute(code)
        } catch (_: RejectedExecutionException) {
            rejected.incrementAndGet()
        }
    }
}
