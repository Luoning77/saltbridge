package com.saltbridge.http

import com.google.gson.JsonParser
import com.saltbridge.core.Clock
import com.saltbridge.core.FakeClock
import com.saltbridge.core.LyricsRead
import com.saltbridge.core.Snapshot
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ControlEndpointTest {

    private val clock = FakeClock()
    private val config = HttpConfig(port = 8737, requestsPerSecond = 10_000)

    private class FakeDeps(
        override val config: HttpConfig,
        override val clock: Clock,
    ) : ApiDeps {
        var admission: ControlAdmission = ControlAdmission.Accepted(
            body = ControlAcceptedJson(
                accepted = true,
                requestId = "req-000001",
                command = "play",
                deduplicated = false,
                acceptedAtMs = 100,
                instanceId = "inst-1",
                generation = 3,
            ),
            deduplicated = false,
        )
        var captured: ControlRequest? = null

        override fun boundPort(): Int = 8737
        override fun isReady(): Boolean = true
        override fun instanceId(): String = "inst-1"
        override fun pluginId(): String = "com.saltbridge"
        override fun pluginVersion(): String = "0.1.0"
        override fun hostVersion(): String = "1.19.2"
        override fun hostChannel(): String = "Steam"
        override fun compiledApiVersion(): String = "0.1.0-dev23"
        override fun snapshot(): Snapshot? = null
        override fun lyrics(): LyricsRead? = null
        override fun libraryReadGranted(): Boolean = true
        override fun cover(generation: Long, trackId: String): CoverResult = CoverResult.Unavailable
        override fun token(): String = "sb_test"

        override fun admitControl(request: ControlRequest): ControlAdmission {
            captured = request
            return admission
        }

        override fun controlSupported(): Boolean = true
    }

    private fun post(
        body: String?,
        contentType: String? = "application/json",
        method: String = "POST",
    ): ApiResponse = ApiRouter(FakeDeps(config, clock)).handle(
        ApiRequest(
            method = method,
            uri = "/api/v1/control",
            headers = mapOf(
                "host" to "127.0.0.1:8737",
                "authorization" to "Bearer sb_test",
            ),
            body = body,
            contentType = contentType,
        ),
    )

    private fun postAndDeps(body: String): Pair<ApiResponse, FakeDeps> {
        val deps = FakeDeps(config, clock)
        val response = ApiRouter(deps).handle(
            ApiRequest(
                method = "POST",
                uri = "/api/v1/control",
                headers = mapOf(
                    "host" to "127.0.0.1:8737",
                    "authorization" to "Bearer sb_test",
                ),
                body = body,
                contentType = "application/json",
            ),
        )
        return response to deps
    }

    private fun valid(
        requestId: String = "req-000001",
        command: String = "play",
        instanceId: String = "inst-1",
        position: String? = null,
        generation: String? = null,
    ): String = buildString {
        append('{')
        append("\"requestId\":\"").append(requestId).append("\",")
        append("\"expectedInstanceId\":\"").append(instanceId).append("\",")
        append("\"command\":\"").append(command).append('"')
        position?.let { append(",\"positionMs\":").append(it) }
        generation?.let { append(",\"expectedGeneration\":").append(it) }
        append('}')
    }

    private fun body(response: ApiResponse) =
        JsonParser.parseString(String(response.body, StandardCharsets.UTF_8)).asJsonObject

    private fun code(response: ApiResponse): String =
        body(response).getAsJsonObject("error").get("code").asString

    @Test
    fun `seek rejects fractional and overflowing positions before admission`() {
        for (position in listOf("-0.5", "1.5", "18446744073709551616", "1e100")) {
            val (response, deps) = postAndDeps(valid(command = "seek", position = position, generation = "3"))
            assertEquals(400, response.status, position)
            assertEquals(null, deps.captured, position)
        }
    }

    @Test
    fun `valid play is accepted with 202`() {
        val (response, deps) = postAndDeps(valid())

        assertEquals(202, response.status)
        val json = body(response)
        assertEquals(true, json["accepted"].asBoolean)
        assertEquals(false, json["deduplicated"].asBoolean)
        assertEquals("play", json["command"].asString)
        assertEquals(3L, json["generation"].asLong)

        val captured = assertNotNull(deps.captured)
        assertEquals(ControlCommand.PLAY, captured.command)
        assertEquals("req-000001", captured.requestId)
    }

    @Test
    fun `valid seek passes position and generation`() {
        val (response, deps) = postAndDeps(
            valid(command = "seek", position = "30000", generation = "3"),
        )

        assertEquals(202, response.status)
        val captured = assertNotNull(deps.captured)
        assertEquals(ControlCommand.SEEK, captured.command)
        assertEquals(30_000L, captured.positionMs)
        assertEquals(3L, captured.expectedGeneration)
    }

    @Test
    fun `deduplicated admission becomes 200`() {
        val deps = FakeDeps(config, clock).apply {
            admission = ControlAdmission.Accepted(
                body = ControlAcceptedJson(true, "req-000001", "play", true, 100, "inst-1", 3),
                deduplicated = true,
            )
        }
        val response = ApiRouter(deps).handle(
            ApiRequest(
                method = "POST",
                uri = "/api/v1/control",
                headers = mapOf("host" to "127.0.0.1:8737", "authorization" to "Bearer sb_test"),
                body = valid(),
                contentType = "application/json",
            ),
        )

        assertEquals(200, response.status)
        assertEquals(true, body(response)["deduplicated"].asBoolean)
    }

    @Test
    fun `rejected admission passes status and code through`() {
        val deps = FakeDeps(config, clock).apply {
            admission = ControlAdmission.Rejected(429, "queue_full", "full", mapOf("capacity" to 32))
        }
        val response = ApiRouter(deps).handle(
            ApiRequest(
                method = "POST",
                uri = "/api/v1/control",
                headers = mapOf("host" to "127.0.0.1:8737", "authorization" to "Bearer sb_test"),
                body = valid(),
                contentType = "application/json",
            ),
        )

        assertEquals(429, response.status)
        assertEquals("queue_full", code(response))
        assertEquals("1", response.headers["Retry-After"], "429 必须带 Retry-After")
    }

    @Test
    fun `missing requestId is rejected`() {
        val response = post("""{"expectedInstanceId":"inst-1","command":"play"}""")
        assertEquals(400, response.status)
        assertEquals("request_id_required", code(response))
    }

    @Test
    fun `short requestId is rejected`() {
        val response = post(valid(requestId = "abc"))
        assertEquals(400, response.status)
        assertEquals("invalid_request_id", code(response))
    }

    @Test
    fun `missing instance id is rejected`() {
        val response = post("""{"requestId":"req-000001","command":"play"}""")
        assertEquals(400, response.status)
        assertEquals("instance_required", code(response))
    }

    @Test
    fun `unknown command is rejected with supported list`() {
        val response = post(valid(command = "volume"))
        assertEquals(400, response.status)
        assertEquals("invalid_command", code(response))

        val detail = body(response).getAsJsonObject("error").getAsJsonObject("detail")
        assertEquals(5, detail.getAsJsonArray("supported").size())
    }

    @Test
    fun `seek without position is rejected`() {
        val response = post(valid(command = "seek", generation = "3"))
        assertEquals(400, response.status)
        assertEquals("invalid_position", code(response))
    }

    @Test
    fun `seek without generation is rejected`() {
        val response = post(valid(command = "seek", position = "30000"))
        assertEquals(400, response.status)
        assertEquals("generation_required", code(response))
    }

    @Test
    fun `non seek command with position is rejected`() {
        val response = post(valid(command = "play", position = "1000"))
        assertEquals(400, response.status)
        assertEquals("unexpected_parameter", code(response))
    }

    @Test
    fun `negative position is rejected`() {
        val response = post(valid(command = "seek", position = "-1", generation = "3"))
        assertEquals(400, response.status)
        assertEquals("position_out_of_range", code(response))
    }

    @Test
    fun `get control is method not allowed`() {
        val response = post(valid(), method = "GET")
        assertEquals(405, response.status)
        assertEquals("method_not_allowed", code(response))
    }

    @Test
    fun `missing content type is rejected`() {
        val response = post(valid(), contentType = null)
        assertEquals(415, response.status)
        assertEquals("unsupported_media_type", code(response))
    }

    @Test
    fun `malformed json is rejected`() {
        val response = post("{not json")
        assertEquals(400, response.status)
        assertEquals("bad_request", code(response))
    }
}
