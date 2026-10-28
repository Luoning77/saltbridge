package com.saltbridge.http

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * NanoHTTPD 适配层：只做协议转换，路由与业务全在 [ApiRouter]。
 *
 * 绑定地址是 `127.0.0.1`（不用 `getLoopbackAddress()`：它可能选 IPv6，
 * 与协议声明的 Host 白名单不一致）。
 */
class BridgeHttpServer(
    private val config: HttpConfig,
    private val router: ApiRouter,
) {

    private var server: NanoHTTPD? = null
    private var runner: BoundedAsyncRunner? = null

    val rejectedRequests: Long
        get() = runner?.rejected?.get() ?: 0L

    fun start(): Boolean {
        if (server != null) return true

        val bounded = BoundedAsyncRunner(config.httpWorkers, config.httpQueue)
        val instance = object : NanoHTTPD(config.host, config.port) {
            override fun serve(session: IHTTPSession): Response = dispatch(session)
        }
        instance.setAsyncRunner(bounded)

        return try {
            instance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = instance
            runner = bounded
            true
        } catch (_: Throwable) {
            runCatching { bounded.closeAll() }
            runCatching { instance.stop() }
            server = null
            runner = null
            false
        }
    }

    fun stop() {
        // 清除中断标志：NanoHTTPD.stop() 内部 Thread.join() 被中断会打日志到 stderr
        Thread.interrupted()
        server?.let { runCatching { it.stop() } }
        runner?.let { runCatching { it.closeAll() } }
        server = null
        runner = null
    }

    /** 实际监听端口；未启动时回退到配置值。测试会用 0 拿随机端口。 */
    fun boundPort(): Int = server?.listeningPort ?: config.port

    private fun dispatch(session: IHTTPSession): Response {
        val headers = session.headers.orEmpty().mapKeys { it.key.lowercase() }
        val isPost = session.method.name.equals("POST", ignoreCase = true)

        var body: String? = null
        if (isPost) {
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > config.maxRequestBytes) {
                return ApiJson.error(
                    status = 413,
                    code = "payload_too_large",
                    message = "Request body exceeds ${config.maxRequestBytes} bytes",
                    detail = mapOf("maxBytes" to config.maxRequestBytes),
                ).toNano()
            }
            body = runCatching {
                val files = HashMap<String, String>()
                session.parseBody(files)
                files["postData"]
            }.getOrNull()
        }

        val request = ApiRequest(
            method = session.method.name,
            uri = session.uri,
            query = parseQuery(session.queryParameterString),
            headers = headers,
            body = body,
            contentType = headers["content-type"],
        )

        val response = runCatching { router.handle(request) }.getOrElse {
            ApiJson.error(500, "internal_error", "Unexpected error")
        }
        return response.toNano()
    }

    private fun ApiResponse.toNano(): Response {
        val nanoStatus = Response.Status.lookup(status)
        val nanoResponse = if (contentType.startsWith("application/json")) {
            NanoHTTPD.newFixedLengthResponse(
                nanoStatus,
                contentType,
                String(body, StandardCharsets.UTF_8),
            )
        } else {
            NanoHTTPD.newFixedLengthResponse(
                nanoStatus,
                contentType,
                ByteArrayInputStream(body),
                body.size.toLong(),
            )
        }
        headers.forEach { (name, value) -> nanoResponse.addHeader(name, value) }
        return nanoResponse
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val separator = pair.indexOf('=')
            val key = if (separator >= 0) pair.substring(0, separator) else pair
            val value = if (separator >= 0) pair.substring(separator + 1) else ""
            result[decode(key)] = decode(value)
        }
        return result
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)
}
