package com.saltbridge.http

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.saltbridge.core.Activity
import com.saltbridge.core.Animation
import com.saltbridge.core.AnimationMode
import com.saltbridge.core.AnimationReason
import com.saltbridge.core.Association
import com.saltbridge.core.Clock
import com.saltbridge.core.FakeClock
import com.saltbridge.core.LyricCell
import com.saltbridge.core.LyricLine
import com.saltbridge.core.LyricsPayload
import com.saltbridge.core.LyricsSnapshot
import com.saltbridge.core.LyricsStatus
import com.saltbridge.core.Playback
import com.saltbridge.core.PlaybackState
import com.saltbridge.core.Position
import com.saltbridge.core.PositionSource
import com.saltbridge.core.Snapshot
import com.saltbridge.core.TimingSanity
import com.saltbridge.core.TrackInfo
import com.saltbridge.core.TrackStatus
import com.saltbridge.core.track
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiRouterTest {

    private val clock = FakeClock()
    private val config = HttpConfig(port = 8737, requestsPerSecond = 10_000)

    private class FakeDeps(
        override val config: HttpConfig,
        override val clock: Clock,
    ) : ApiDeps {
        var bound = 8737
        var ready = true
        var instance = "inst-1"
        var snapshotValue: Snapshot? = null
        var lyricsValue: LyricsPayload? = null
        var libraryRead = true
        var coverResult: CoverResult = CoverResult.Unavailable
        var tokenValue = "sb_test"
        var admission: ControlAdmission = ControlAdmission.Accepted(
            body = ControlAcceptedJson(
                accepted = true,
                requestId = "test-request",
                command = "play",
                deduplicated = false,
                acceptedAtMs = 100,
                instanceId = "inst-1",
                generation = 3,
            ),
            deduplicated = false,
        )
        var controlEnabled = true

        override fun boundPort(): Int = bound
        override fun isReady(): Boolean = ready
        override fun instanceId(): String = instance
        override fun pluginId(): String = "com.saltbridge"
        override fun pluginVersion(): String = "0.1.0"
        override fun hostVersion(): String = "1.19.2"
        override fun hostChannel(): String = "Steam"
        override fun compiledApiVersion(): String = "0.1.0-dev23"
        override fun snapshot(): Snapshot? = snapshotValue
        override fun lyrics(): LyricsPayload? = lyricsValue
        override fun libraryReadGranted(): Boolean = libraryRead
        override fun cover(generation: Long, trackId: String): CoverResult = coverResult
        override fun token(): String = tokenValue
        override fun admitControl(request: ControlRequest): ControlAdmission = admission
        override fun controlSupported(): Boolean = controlEnabled
    }

    private fun sampleSnapshot(
        instanceId: String = "inst-1",
        generation: Long = 3,
        trackStatus: TrackStatus = TrackStatus.AVAILABLE,
        track: TrackInfo? = track(),
        lyricsStatus: LyricsStatus = LyricsStatus.AVAILABLE,
    ): Snapshot = Snapshot(
        instanceId = instanceId,
        seq = 128,
        servedAtMs = 900_000,
        generation = generation,
        trackStatus = trackStatus,
        track = track,
        playback = Playback(
            state = PlaybackState.PLAYING,
            isPlaying = true,
            position = Position(reportedMs = 61_234, reportedAtMs = 986_000, source = PositionSource.EVENT),
            activity = Activity.ADVANCING,
            activityUpdatedAtMs = 986_000,
            animation = Animation(AnimationMode.CONFIRMED, AnimationReason.IS_PLAYING, 3_000),
            lastEventAtMs = 985_500,
        ),
        lyrics = LyricsSnapshot(
            status = lyricsStatus,
            association = Association.ASSOCIATED,
            version = 7,
            lineCount = 2,
            staleSinceMs = null,
            staleUntilMs = null,
            timingSanity = TimingSanity.OK,
            updatedAtMs = 972_000,
        ),
    )

    private fun payload(generation: Long = 3): LyricsPayload = LyricsPayload(
        generation = generation,
        status = LyricsStatus.AVAILABLE,
        association = Association.ASSOCIATED,
        version = 7,
        staleSinceMs = null,
        staleUntilMs = null,
        timingSanity = TimingSanity.OK,
        lines = listOf(
            LyricLine(
                startMs = 5_000,
                endMs = 8_000,
                text = "故事的小黄花",
                translation = "translation",
                cells = listOf(LyricCell(5_000, 5_300, "故")),
            ),
            LyricLine(startMs = 8_000, endMs = 11_000, text = "从出生那年就飘着"),
        ),
    )

    private fun deps(): FakeDeps = FakeDeps(config, clock).apply {
        snapshotValue = sampleSnapshot()
        lyricsValue = payload()
    }

    private fun headers(
        token: String? = "sb_test",
        host: String = "127.0.0.1:8737",
    ): Map<String, String> = buildMap {
        put("host", host)
        if (token != null) put("authorization", "Bearer $token")
    }

    private fun get(
        path: String,
        token: String? = "sb_test",
        host: String = "127.0.0.1:8737",
    ): ApiRequest = ApiRequest(
        method = "GET",
        uri = path,
        query = parseQuery(path),
        headers = headers(token, host),
    )

    private fun parseQuery(path: String): Map<String, String> {
        val raw = path.substringAfter('?', "")
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            key to value
        }
    }

    private fun body(response: ApiResponse): JsonObject =
        JsonParser.parseString(String(response.body, StandardCharsets.UTF_8)).asJsonObject

    private fun errorCode(response: ApiResponse): String =
        body(response).getAsJsonObject("error").get("code").asString

    // --- 认证与安全 ---

    @Test
    fun `health does not require token`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/health", token = null))

        assertEquals(200, response.status)
        val json = body(response)
        assertTrue(json["ok"].asBoolean)
        assertEquals("1.0", json["protocolVersion"].asString)
        assertTrue(json["authRequired"].asBoolean)
        assertEquals("inst-1", json["instanceId"].asString)
    }

    @Test
    fun `origin header is rejected`() {
        val request = ApiRequest(
            method = "GET",
            uri = "/api/v1/health",
            headers = mapOf("host" to "127.0.0.1:8737", "origin" to "http://evil"),
        )
        val response = ApiRouter(deps()).handle(request)

        assertEquals(403, response.status)
        assertEquals("forbidden_origin", errorCode(response))
    }

    @Test
    fun `foreign host is rejected`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/health", host = "evil.example:8737"))

        assertEquals(403, response.status)
        assertEquals("forbidden_host", errorCode(response))
    }

    @Test
    fun `status requires a token`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/status", token = null))

        assertEquals(401, response.status)
        assertEquals("unauthorized", errorCode(response))
        assertEquals("Bearer", response.headers["WWW-Authenticate"])
    }

    @Test
    fun `status rejects a wrong token`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/status", token = "wrong"))

        assertEquals(401, response.status)
        assertEquals("unauthorized", errorCode(response))
    }

    @Test
    fun `status returns protocol shaped snapshot`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/status"))

        assertEquals(200, response.status)
        val json = body(response)
        assertEquals("1.0", json["protocolVersion"].asString)
        assertEquals(3L, json["generation"].asLong)
        assertEquals("available", json["trackStatus"].asString)
        assertEquals(0L, json["servedAtMs"].asLong, "servedAtMs 必须是本次响应时刻")

        val playback = json.getAsJsonObject("playback")
        assertEquals("playing", playback["state"].asString)
        assertTrue(playback["isPlaying"].asBoolean)
        assertEquals("confirmed", playback.getAsJsonObject("animation")["mode"].asString)
        assertEquals("advancing", playback["activity"].asString)
        assertEquals(61_234L, playback.getAsJsonObject("position")["reportedMs"].asLong)
    }

    @Test
    fun `capabilities reports control support when enabled`() {
        val deps = deps().apply { controlEnabled = true }
        val response = ApiRouter(deps).handle(get("/api/v1/capabilities"))

        assertEquals(200, response.status)
        val json = body(response)
        val control = json.getAsJsonObject("features").getAsJsonObject("control")
        assertTrue(control["play"].asBoolean)
        assertTrue(control["seek"].asBoolean)
        assertTrue(json.getAsJsonObject("permissions")["libraryRead"].asBoolean)
        assertEquals(8, json.getAsJsonObject("limits")["httpWorkers"].asInt)
        assertEquals("0.1.0-dev23", json.getAsJsonObject("host")["compiledApiVersion"].asString)
    }

    @Test
    fun `capabilities reports no control when unsupported`() {
        val deps = deps().apply { controlEnabled = false }
        val response = ApiRouter(deps).handle(get("/api/v1/capabilities"))

        assertEquals(200, response.status)
        val control = body(response).getAsJsonObject("features").getAsJsonObject("control")
        assertFalse(control["play"].asBoolean)
        assertFalse(control["seek"].asBoolean)
    }

    @Test
    fun `service unavailable while not ready`() {
        val deps = deps().apply { ready = false }
        val response = ApiRouter(deps).handle(get("/api/v1/health", token = null))

        assertEquals(503, response.status)
        assertEquals("service_unavailable", errorCode(response))
    }

    // --- 歌词 ---

    @Test
    fun `lyrics rejects when there is no track`() {
        val deps = deps().apply {
            snapshotValue = sampleSnapshot(trackStatus = TrackStatus.NONE, track = null)
        }
        val response = ApiRouter(deps).handle(get("/api/v1/lyrics"))

        assertEquals(404, response.status)
        assertEquals("no_track", errorCode(response))
    }

    @Test
    fun `lyrics rejects instance mismatch`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/lyrics?expectedInstanceId=other"))

        assertEquals(409, response.status)
        assertEquals("instance_mismatch", errorCode(response))
        assertEquals(
            "inst-1",
            body(response).getAsJsonObject("error").getAsJsonObject("detail")["currentInstanceId"].asString,
        )
    }

    @Test
    fun `lyrics rejects generation mismatch`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/lyrics?expectedGeneration=2"))

        assertEquals(409, response.status)
        assertEquals("generation_mismatch", errorCode(response))
        assertEquals(
            3L,
            body(response).getAsJsonObject("error").getAsJsonObject("detail")["currentGeneration"].asLong,
        )
    }

    @Test
    fun `lyrics returns lines with timesValid`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/lyrics?expectedGeneration=3"))

        assertEquals(200, response.status)
        val json = body(response)
        assertEquals("trackStart", json["timesBase"].asString)
        assertEquals("available", json["status"].asString)
        assertEquals(2, json["lines"].asJsonArray.size())

        val first = json["lines"].asJsonArray[0].asJsonObject
        assertEquals("故事的小黄花", first["text"].asString)
        assertTrue(first["timesValid"].asBoolean)
        assertEquals("word", first["timing"].asString)
        assertEquals("故", first["cells"].asJsonArray[0].asJsonObject["text"].asString)

        val second = json["lines"].asJsonArray[1].asJsonObject
        assertEquals("line", second["timing"].asString)
        assertEquals(0, second["cells"].asJsonArray.size())
    }

    @Test
    fun `lyrics rejects non numeric generation`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/lyrics?expectedGeneration=abc"))

        assertEquals(400, response.status)
        assertEquals("bad_request", errorCode(response))
    }

    // --- 封面 ---

    @Test
    fun `cover requires library permission`() {
        val deps = deps().apply { libraryRead = false }
        val response = ApiRouter(deps).handle(get("/api/v1/cover"))

        assertEquals(403, response.status)
        assertEquals("permission_denied", errorCode(response))
    }

    @Test
    fun `cover without library id is unavailable`() {
        val deps = deps().apply { snapshotValue = sampleSnapshot(track = track(id = null)) }
        val response = ApiRouter(deps).handle(get("/api/v1/cover"))

        assertEquals(404, response.status)
        assertEquals("cover_unavailable", errorCode(response))
    }

    @Test
    fun `cover pending returns 202 with retry hint`() {
        val deps = deps().apply { coverResult = CoverResult.Pending }
        val response = ApiRouter(deps).handle(get("/api/v1/cover"))

        assertEquals(202, response.status)
        val json = body(response)
        assertEquals("pending", json["status"].asString)
        assertEquals(3L, json["generation"].asLong)
        assertEquals(500L, json["retryAfterMs"].asLong)
    }

    @Test
    fun `cover ready returns binary with generation header`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val deps = deps().apply { coverResult = CoverResult.Ready(bytes, "image/jpeg") }
        val response = ApiRouter(deps).handle(get("/api/v1/cover"))

        assertEquals(200, response.status)
        assertEquals("image/jpeg", response.contentType)
        assertEquals("3", response.headers["X-SaltBridge-Generation"])
        assertEquals("inst-1", response.headers["X-SaltBridge-Instance-Id"])
        assertEquals("no-store", response.headers["Cache-Control"])
        assertTrue(bytes.contentEquals(response.body))
    }

    @Test
    fun `cover unavailable carries generation header`() {
        val deps = deps().apply { coverResult = CoverResult.Unavailable }
        val response = ApiRouter(deps).handle(get("/api/v1/cover"))

        assertEquals(404, response.status)
        assertEquals("cover_unavailable", errorCode(response))
        assertEquals("3", response.headers["X-SaltBridge-Generation"])
    }

    // --- 路由与限制 ---

    @Test
    fun `options is method not allowed`() {
        val request = ApiRequest("OPTIONS", "/api/v1/status", emptyMap(), headers())
        val response = ApiRouter(deps()).handle(request)

        assertEquals(405, response.status)
        assertEquals("method_not_allowed", errorCode(response))
    }

    @Test
    fun `post to status is method not allowed`() {
        val request = ApiRequest("POST", "/api/v1/status", emptyMap(), headers())
        val response = ApiRouter(deps()).handle(request)

        assertEquals(405, response.status)
    }

    @Test
    fun `control with empty body is bad request`() {
        val request = ApiRequest(
            method = "POST",
            uri = "/api/v1/control",
            headers = headers(),
            contentType = "application/json",
            body = null,
        )
        val response = ApiRouter(deps()).handle(request)

        assertEquals(400, response.status)
        assertEquals("bad_request", errorCode(response))
    }

    @Test
    fun `control requires json content type`() {
        val request = ApiRequest(
            method = "POST",
            uri = "/api/v1/control",
            headers = headers(),
            contentType = "text/plain",
            body = "{}",
        )
        val response = ApiRouter(deps()).handle(request)

        assertEquals(415, response.status)
        assertEquals("unsupported_media_type", errorCode(response))
    }

    @Test
    fun `unknown path is not found`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/nope"))

        assertEquals(404, response.status)
        assertEquals("not_found", errorCode(response))
    }

    @Test
    fun `long uri is rejected`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/status?x=" + "a".repeat(3_000)))

        assertEquals(414, response.status)
        assertEquals("uri_too_long", errorCode(response))
    }

    @Test
    fun `rate limit rejects beyond configured rate`() {
        val limited = FakeDeps(HttpConfig(port = 8737, requestsPerSecond = 2), clock).apply {
            snapshotValue = sampleSnapshot()
        }
        val router = ApiRouter(limited)

        assertEquals(200, router.handle(get("/api/v1/status")).status)
        assertEquals(200, router.handle(get("/api/v1/status")).status)
        val third = router.handle(get("/api/v1/status"))

        assertEquals(429, third.status)
        assertEquals("rate_limited", errorCode(third))
    }

    @Test
    fun `error envelope carries a request id`() {
        val response = ApiRouter(deps()).handle(get("/api/v1/nope"))
        val error = body(response).getAsJsonObject("error")

        assertNotNull(error["requestId"])
        assertFalse(error["requestId"].asString.isBlank())
    }
}
