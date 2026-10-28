package com.saltbridge.http

import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets

/**
 * HTTP 层参数。默认值与协议 `capabilities.limits` 一一对应。
 *
 * 端口只允许通过 `config.json` 的 `http.port` 修改；配置界面没有数字输入控件（M0 已记录）。
 */
data class HttpConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8737,
    val protocolVersion: String = "1.0",
    val maxUrlBytes: Int = 2_048,
    val maxRequestBytes: Int = 4_096,
    val httpWorkers: Int = 8,
    val httpQueue: Int = 64,
    val requestsPerSecond: Int = 50,
    val coverMaxBytes: Long = 16L * 1024 * 1024,
    val coverPendingRetryAfterMs: Long = 500,
    val coverPendingTimeoutMs: Long = 3_000,
    val coverFailureTtlMs: Long = 30_000,
    val coverCacheEntries: Int = 8,
    val positionStaleAfterMs: Long = 3_000,
    val inferredAnimationMaxMs: Long = 1_500,
    val lyricsStaleGraceMs: Long = 2_000,
    val controlQueue: Int = 32,
    val dedupeWindowMs: Long = 600_000,
    val dedupeCapacity: Int = 1_024,
)

/** 已解析的请求。`headers` 的键必须是**小写**。 */
data class ApiRequest(
    val method: String,
    val uri: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    /** 请求体原文；GET 为 null，POST 由适配层读入。 */
    val body: String? = null,
    val contentType: String? = null,
)

/** 响应。`body` 已编码为字节，HTTP 适配层只负责写出。 */
data class ApiResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
)

data class ErrorEnvelope(val error: ErrorBody)

data class ErrorBody(
    val code: String,
    val message: String,
    val detail: Any? = null,
    val requestId: String? = null,
)

object ApiJson {

    private val gson = GsonBuilder().serializeNulls().create()

    fun encode(value: Any): ByteArray =
        gson.toJson(value).toByteArray(StandardCharsets.UTF_8)

    fun json(status: Int, value: Any, headers: Map<String, String> = emptyMap()): ApiResponse =
        ApiResponse(
            status = status,
            contentType = "application/json; charset=utf-8",
            body = encode(value),
            headers = headers,
        )

    fun binary(
        status: Int,
        contentType: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = ApiResponse(
        status = status,
        contentType = contentType,
        body = bytes,
        headers = headers,
    )

    fun error(
        status: Int,
        code: String,
        message: String,
        detail: Any? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = json(
        status,
        ErrorEnvelope(ErrorBody(code = code, message = message, detail = detail, requestId = requestId())),
        headers,
    )

    private fun requestId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8)
}

/** 主机 / 来源 / 令牌检查的失败结果，由 Router 翻译成带 `requestId` 的响应。 */
data class Rejection(val status: Int, val code: String, val message: String)
