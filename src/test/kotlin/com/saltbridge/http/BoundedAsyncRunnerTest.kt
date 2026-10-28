package com.saltbridge.http

import fi.iki.elonen.NanoHTTPD
import java.io.FilterInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class BoundedAsyncRunnerTest {
    private class Server : NanoHTTPD(0) {
        fun handler(socket: Socket, started: CountDownLatch): ClientHandler =
            createClientHandler(socket, object : FilterInputStream(socket.getInputStream()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    started.countDown()
                    return super.read(bytes, offset, length)
                }
            })
    }

    @Test
    fun `overload and stop close rejected queued and active sockets`() {
        val runner = BoundedAsyncRunner(1, 1)
        val server = Server()
        val sockets = mutableListOf<Socket>()
        ServerSocket(0).use { listener ->
            fun connection(): Socket {
                sockets += Socket("127.0.0.1", listener.localPort)
                return listener.accept().also { sockets += it }
            }
            val active = connection()
            val queued = connection()
            val rejected = connection()
            val started = CountDownLatch(1)
            try {
                runner.exec(server.handler(active, started))
                assertTrue(started.await(2, TimeUnit.SECONDS), "worker must be reading")
                runner.exec(server.handler(queued, CountDownLatch(1)))
                runner.exec(server.handler(rejected, CountDownLatch(1)))
                assertTrue(rejected.isClosed, "rejected handler must close its socket")
                runner.closeAll()
                assertTrue(queued.isClosed, "queued handler must close its socket")
                assertTrue(active.isClosed, "active read must be unblocked by closing socket")
                val afterStop = connection()
                runner.exec(server.handler(afterStop, CountDownLatch(1)))
                assertTrue(afterStop.isClosed, "submission after shutdown must close socket")
            } finally {
                runner.closeAll()
                sockets.forEach { it.close() }
            }
        }
    }
}
