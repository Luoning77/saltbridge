package com.saltbridge.probe

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 探测宿主 JRE 是否具备 `jdk.httpserver` 模块，并做一次真实的回环请求。
 *
 * 全程使用临时端口（port=0），不占用用户配置端口；失败只返回结果，不抛异常。
 */
object HttpProbe {

    fun run(): HttpProbeResult {
        val started = System.nanoTime()
        var server: HttpServer? = null
        var pool: ExecutorService? = null
        return try {
            Class.forName("com.sun.net.httpserver.HttpServer")

            val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server = created
            pool = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "saltbridge-probe-http").apply { isDaemon = true }
            }
            created.executor = pool
            created.createContext("/ping") { exchange ->
                val body = "pong".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            created.start()

            val port = created.address.port
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/ping"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            HttpProbeResult(
                available = response.statusCode() == 200 && response.body() == "pong",
                detail = "status=${response.statusCode()} body=${response.body()}",
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                port = port
            )
        } catch (t: Throwable) {
            HttpProbeResult(
                available = false,
                detail = "${t.javaClass.name}: ${t.message}",
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                port = null
            )
        } finally {
            runCatching { server?.stop(0) }
            runCatching { pool?.shutdownNow() }
        }
    }
}
