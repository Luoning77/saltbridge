package com.saltbridge.http

import com.google.gson.JsonParser
import com.saltbridge.core.TrackStatus
import com.saltbridge.wire.CapabilitiesJson
import com.saltbridge.wire.ControlFeaturesJson
import com.saltbridge.wire.CoverPendingJson
import com.saltbridge.wire.FeaturesJson
import com.saltbridge.wire.HealthJson
import com.saltbridge.wire.HostInfoJson
import com.saltbridge.wire.LimitsJson
import com.saltbridge.wire.LyricsMapper
import com.saltbridge.wire.PermissionsJson
import com.saltbridge.wire.PluginInfoJson
import com.saltbridge.wire.SnapshotJson

private enum class Endpoint { HEALTH, CAPABILITIES, STATUS, LYRICS, COVER, CONTROL, LIBRARY }

private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

/** 时长未知时的位置上界（协议 5.6）。 */
private const val MAX_POSITION_MS = 86_400_000L

/**
 * 只读端点 + 控制端点路由。
 *
 * 判定顺序固定：URL 长度 → Host → Origin → 方法 → 路径 → 限流 → 令牌 → 就绪 → 处理。
 * 限流放在鉴权之前，避免暴力猜令牌不受限。
 *
 * `/control` 的处理顺序（协议 5.6）在 [control] 里固定，不可调换。
 */
class ApiRouter(
    private val deps: ApiDeps,
    private val rateLimiter: RateLimiter = RateLimiter(deps.config.requestsPerSecond, deps.clock),
) {

    fun handle(request: ApiRequest): ApiResponse {
        val config = deps.config

        if (request.uri.length > config.maxUrlBytes) {
            return error(
                status = 414,
                code = "uri_too_long",
                message = "URI exceeds ${config.maxUrlBytes} characters",
                detail = mapOf("maxBytes" to config.maxUrlBytes),
            )
        }

        AuthGuard.checkHost(request.headers, deps.boundPort())?.let { return it.toResponse() }
        AuthGuard.checkOrigin(request.headers)?.let { return it.toResponse() }

        if (request.method.equals("OPTIONS", ignoreCase = true)) return methodNotAllowed("GET")

        val path = request.uri.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        val endpoint = when (path) {
            "/api/v1/health" -> Endpoint.HEALTH
            "/api/v1/capabilities" -> Endpoint.CAPABILITIES
            "/api/v1/status" -> Endpoint.STATUS
            "/api/v1/lyrics" -> Endpoint.LYRICS
            "/api/v1/cover" -> Endpoint.COVER
            "/api/v1/library/tracks" -> Endpoint.LIBRARY
            "/api/v1/control" -> Endpoint.CONTROL
            else -> return error(404, "not_found", "No such endpoint: $path")
        }

        if (endpoint == Endpoint.CONTROL) {
            if (!request.method.equals("POST", ignoreCase = true)) return methodNotAllowed("POST")
        } else if (!request.method.equals("GET", ignoreCase = true)) {
            return methodNotAllowed("GET")
        }

        if (!rateLimiter.tryAcquire()) {
            return error(429, "rate_limited", "Too many requests")
        }

        if (endpoint != Endpoint.HEALTH) {
            AuthGuard.checkToken(request.headers, deps.token())?.let { return it.toResponse() }
        }

        if (!deps.isReady()) return unavailable()

        return when (endpoint) {
            Endpoint.HEALTH -> health()
            Endpoint.CAPABILITIES -> capabilities()
            Endpoint.STATUS -> status()
            Endpoint.LYRICS -> lyrics(request)
            Endpoint.COVER -> cover(request)
            Endpoint.LIBRARY -> library(request)
            Endpoint.CONTROL -> control(request)
        }
    }

    // --- 端点 ---

    private fun library(request: ApiRequest): ApiResponse {
        if (request.query.keys.any { it !in setOf("afterId", "limit") }) return error(400, "bad_request", "Unknown query parameter")
        val rawLimit = request.query["limit"]
        val limit = if (rawLimit == null) 50 else rawLimit.toIntOrNull()
        if (limit == null || limit !in 1..200) return error(400, "bad_request", "limit must be between 1 and 200")
        val cursor = request.query["afterId"]
        if (cursor != null && (cursor.isBlank() || cursor.length > 512)) return error(400, "bad_request", "Invalid cursor")
        if (!deps.libraryReadGranted()) return error(403, "permission_denied", "Library read permission required")
        return when (val result = deps.libraryTracks(cursor, limit)) {
            LibraryResult.Pending -> ApiJson.json(202, mapOf("instanceId" to deps.instanceId(), "status" to "pending", "retryAfterMs" to 500))
            is LibraryResult.Ready -> ApiJson.json(200, mapOf("instanceId" to deps.instanceId(), "tracks" to result.page.tracks, "nextCursor" to result.page.nextCursor))
            is LibraryResult.Failed -> if (result.forbidden) error(403, "permission_denied", "Library read permission required") else error(503, "library_unavailable", "Library query failed; retry later")
        }
    }

    private fun health(): ApiResponse = ApiJson.json(
        200,
        HealthJson(
            ok = true,
            protocolVersion = deps.config.protocolVersion,
            authRequired = true,
            instanceId = deps.instanceId(),
            pluginVersion = deps.pluginVersion(),
            hostVersion = deps.hostVersion(),
            servedAtMs = deps.clock.nowMs(),
        ),
    )

    private fun capabilities(): ApiResponse {
        val config = deps.config
        return ApiJson.json(
            200,
            CapabilitiesJson(
                protocolVersion = config.protocolVersion,
                instanceId = deps.instanceId(),
                servedAtMs = deps.clock.nowMs(),
                plugin = PluginInfoJson(id = deps.pluginId(), version = deps.pluginVersion()),
                host = HostInfoJson(
                    spwVersion = deps.hostVersion(),
                    channel = deps.hostChannel(),
                    compiledApiVersion = deps.compiledApiVersion(),
                ),
                permissions = PermissionsJson(libraryRead = deps.libraryReadGranted()),
                features = FeaturesJson(
                    library = deps.librarySupported(),
                    status = true,
                    lyrics = true,
                    cover = true,
                    control = if (deps.controlSupported()) {
                        ControlFeaturesJson(
                            play = true,
                            pause = true,
                            next = true,
                            previous = true,
                            seek = true,
                        )
                    } else {
                        ControlFeaturesJson(
                            play = false,
                            pause = false,
                            next = false,
                            previous = false,
                            seek = false,
                        )
                    },
                    longPoll = false,
                    webSocket = false,
                ),
                limits = LimitsJson(
                    maxRequestBytes = config.maxRequestBytes,
                    maxUrlBytes = config.maxUrlBytes,
                    controlQueue = config.controlQueue,
                    dedupeWindowMs = config.dedupeWindowMs,
                    dedupeCapacity = config.dedupeCapacity,
                    positionStaleAfterMs = config.positionStaleAfterMs,
                    inferredAnimationMaxMs = config.inferredAnimationMaxMs,
                    lyricsStaleGraceMs = config.lyricsStaleGraceMs,
                    coverMaxBytes = config.coverMaxBytes,
                    coverPendingRetryAfterMs = config.coverPendingRetryAfterMs,
                    coverPendingTimeoutMs = config.coverPendingTimeoutMs,
                    requestsPerSecond = config.requestsPerSecond,
                    httpWorkers = config.httpWorkers,
                    httpQueue = config.httpQueue,
                ),
                assumptions = emptyList(),
            ),
        )
    }

    private fun status(): ApiResponse {
        val snapshot = deps.snapshot() ?: return unavailable()
        return ApiJson.json(200, SnapshotJson.of(snapshot, deps.clock.nowMs()))
    }

    private fun lyrics(request: ApiRequest): ApiResponse {
        val expectedGeneration = request.query["expectedGeneration"]?.let { raw ->
            raw.toLongOrNull()
                ?: return error(400, "bad_request", "expectedGeneration must be an integer")
        }
        val expectedInstanceId = request.query["expectedInstanceId"]

        val read = deps.lyrics() ?: return unavailable()
        val snapshot = read.snapshot
        val payload = read.payload

        if (snapshot.trackStatus == TrackStatus.NONE) {
            return error(404, "no_track", "No current track")
        }
        if (expectedInstanceId != null && expectedInstanceId != snapshot.instanceId) {
            return error(
                status = 409,
                code = "instance_mismatch",
                message = "Plugin was restarted",
                detail = mapOf("currentInstanceId" to snapshot.instanceId),
            )
        }
        if (expectedGeneration != null && expectedGeneration != snapshot.generation) {
            return error(
                status = 409,
                code = "generation_mismatch",
                message = "Track changed",
                detail = mapOf(
                    "currentGeneration" to snapshot.generation,
                    "currentTrackId" to snapshot.track?.id,
                ),
            )
        }

        return ApiJson.json(200, LyricsMapper.map(payload, snapshot.instanceId, deps.clock.nowMs()))
    }

    private fun cover(request: ApiRequest): ApiResponse {
        val expectedGeneration = request.query["expectedGeneration"]?.let { raw ->
            raw.toLongOrNull()
                ?: return error(400, "bad_request", "expectedGeneration must be an integer")
        }
        val expectedInstanceId = request.query["expectedInstanceId"]

        val snapshot = deps.snapshot() ?: return unavailable()

        if (expectedInstanceId != null && expectedInstanceId != deps.instanceId()) {
            return error(
                status = 409,
                code = "instance_mismatch",
                message = "Plugin was restarted",
                detail = mapOf("currentInstanceId" to deps.instanceId()),
            )
        }
        if (expectedGeneration != null && expectedGeneration != snapshot.generation) {
            return error(
                status = 409,
                code = "generation_mismatch",
                message = "Track changed",
                detail = mapOf(
                    "currentGeneration" to snapshot.generation,
                    "currentTrackId" to snapshot.track?.id,
                ),
            )
        }

        if (!deps.libraryReadGranted()) {
            return error(
                status = 403,
                code = "permission_denied",
                message = "Library read permission is required",
                detail = mapOf("permission" to "library-read"),
            )
        }
        if (snapshot.trackStatus != TrackStatus.AVAILABLE) {
            return error(404, "no_track", "No current track")
        }
        val trackId = snapshot.track?.id
            ?: return error(
                status = 404,
                code = "cover_unavailable",
                message = "Track has no library id",
                detail = mapOf("generation" to snapshot.generation),
            )

        val headers = mapOf("X-SaltBridge-Generation" to snapshot.generation.toString())

        return when (val result = deps.cover(snapshot.generation, trackId)) {
            is CoverResult.Ready -> ApiJson.binary(
                status = 200,
                contentType = result.contentType,
                bytes = result.bytes,
                headers = headers + mapOf(
                    "X-SaltBridge-Instance-Id" to deps.instanceId(),
                    "Cache-Control" to "no-store",
                ),
            )

            CoverResult.Pending -> ApiJson.json(
                202,
                CoverPendingJson(
                    status = "pending",
                    generation = snapshot.generation,
                    retryAfterMs = deps.config.coverPendingRetryAfterMs,
                ),
            )

            CoverResult.Unavailable -> error(
                status = 404,
                code = "cover_unavailable",
                message = "Cover is not available",
                detail = mapOf("generation" to snapshot.generation),
                headers = headers,
            )
        }
    }

    /**
     * `POST /api/v1/control`（协议 5.6）。
     *
     * 这里只做 ①②：内容类型、JSON 语法、字段级校验。
     * ③④⑤（去重、实例/代次校验、记录 + 入队）在 [ControlQueue] 的一把锁内完成。
     */
    private fun control(request: ApiRequest): ApiResponse {
        val contentType = request.contentType?.lowercase()
        if (contentType == null || !contentType.startsWith("application/json")) {
            return error(
                status = 415,
                code = "unsupported_media_type",
                message = "Content-Type must be application/json",
            )
        }

        val raw = request.body
        if (raw.isNullOrBlank()) {
            return error(400, "bad_request", "Request body is empty")
        }

        val root = runCatching {
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: return error(400, "bad_request", "Request body is not a JSON object")

        fun text(name: String): String? =
            root[name]?.takeIf { it.isJsonPrimitive }?.asString

        val requestId = text("requestId")
        if (requestId.isNullOrBlank()) {
            return error(400, "request_id_required", "requestId is required")
        }
        if (!REQUEST_ID.matches(requestId)) {
            return error(
                status = 400,
                code = "invalid_request_id",
                message = "requestId must be 8-64 characters of [A-Za-z0-9_-]",
            )
        }

        val expectedInstanceId = text("expectedInstanceId")
            ?: return error(400, "instance_required", "expectedInstanceId is required")

        val commandName = text("command")
            ?: return error(
                status = 400,
                code = "invalid_command",
                message = "command is required",
                detail = mapOf("supported" to ControlCommand.supported),
            )
        val command = ControlCommand.fromWire(commandName)
            ?: return error(
                status = 400,
                code = "invalid_command",
                message = "Unsupported command: $commandName",
                detail = mapOf("supported" to ControlCommand.supported),
            )

        var positionMs: Long? = null
        val rawPosition = root["positionMs"]
        if (rawPosition != null) {
            if (!rawPosition.isJsonPrimitive || !rawPosition.asJsonPrimitive.isNumber) {
                return error(400, "invalid_position", "positionMs must be an integer")
            }
            positionMs = rawPosition.asString.toLongOrNull()
                ?: return error(400, "invalid_position", "positionMs must be an integer within Long range")
            if (positionMs < 0) {
                return error(
                    status = 400,
                    code = "position_out_of_range",
                    message = "positionMs must not be negative",
                    detail = mapOf("maxMs" to MAX_POSITION_MS),
                )
            }
        }

        if (command == ControlCommand.SEEK) {
            if (positionMs == null) {
                return error(400, "invalid_position", "seek requires positionMs")
            }
        } else if (positionMs != null) {
            return error(
                status = 400,
                code = "unexpected_parameter",
                message = "positionMs is only allowed for seek",
            )
        }

        val expectedGeneration: Long? = text("expectedGeneration")?.let { rawGeneration ->
            rawGeneration.toLongOrNull()
                ?: return error(400, "bad_request", "expectedGeneration must be an integer")
        }
        if (command == ControlCommand.SEEK && expectedGeneration == null) {
            return error(
                status = 400,
                code = "generation_required",
                message = "seek requires expectedGeneration",
            )
        }

        val parsed = ControlRequest(
            requestId = requestId,
            expectedInstanceId = expectedInstanceId,
            command = command,
            positionMs = positionMs,
            expectedGeneration = expectedGeneration,
        )

        return when (val admission = deps.admitControl(parsed)) {
            is ControlAdmission.Accepted -> ApiJson.json(
                status = if (admission.deduplicated) 200 else 202,
                value = admission.body,
            )

            is ControlAdmission.Rejected -> ApiJson.error(
                status = admission.status,
                code = admission.code,
                message = admission.message,
                detail = admission.detail,
                headers = if (admission.status == 429) mapOf("Retry-After" to "1") else emptyMap(),
            )
        }
    }

    // --- 辅助 ---

    private fun methodNotAllowed(allowed: String): ApiResponse = error(
        status = 405,
        code = "method_not_allowed",
        message = "Only $allowed is allowed",
        detail = mapOf("allowed" to listOf(allowed)),
    )

    private fun unavailable(): ApiResponse = error(
        status = 503,
        code = "service_unavailable",
        message = "Service is not ready",
        detail = mapOf("reason" to "stopping"),
    )

    private fun error(
        status: Int,
        code: String,
        message: String,
        detail: Any? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = ApiJson.error(status, code, message, detail, headers)

    private fun Rejection.toResponse(): ApiResponse = ApiJson.error(
        status = status,
        code = code,
        message = message,
        headers = if (status == 401) mapOf("WWW-Authenticate" to "Bearer") else emptyMap(),
    )
}
