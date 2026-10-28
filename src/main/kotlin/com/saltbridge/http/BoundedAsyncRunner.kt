package com.saltbridge.http

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 固定线程与有界队列；拒绝和停止时主动关闭连接，解除阻塞中的读取。 */
class BoundedAsyncRunner(workers: Int, queueCapacity: Int) : NanoHTTPD.AsyncRunner {
    val rejected = AtomicLong()
    private val lock = Any()
    private val handlers = mutableSetOf<NanoHTTPD.ClientHandler>()
    private var stopped = false
    private val executor = ThreadPoolExecutor(
        workers, workers, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        ThreadFactory { runnable -> Thread(runnable, "saltbridge-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun closeAll() {
        val toClose = synchronized(lock) {
            stopped = true
            executor.shutdownNow()
            handlers.toList().also { handlers.clear() }
        }
        toClose.forEach { it.close() }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        synchronized(lock) { handlers.remove(clientHandler) }
    }

    override fun exec(code: NanoHTTPD.ClientHandler) {
        val accepted = synchronized(lock) {
            if (stopped) false else {
                handlers.add(code)
                try {
                    executor.execute {
                        try { code.run() } finally { closed(code) }
                    }
                    true
                } catch (_: RejectedExecutionException) {
                    handlers.remove(code)
                    false
                }
            }
        }
        if (!accepted) {
            rejected.incrementAndGet()
            code.close()
        }
    }
}
