package com.saltbridge.http

import com.saltbridge.core.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ControlQueueTest {

    private val clock = FakeClock()
    private val instance = "inst-1"

    private fun request(
        id: String = "req-000001",
        command: ControlCommand = ControlCommand.PLAY,
        positionMs: Long? = null,
        expectedGeneration: Long? = null,
        expectedInstanceId: String = instance,
    ) = ControlRequest(
        requestId = id,
        expectedInstanceId = expectedInstanceId,
        command = command,
        positionMs = positionMs,
        expectedGeneration = expectedGeneration,
    )

    private fun queue(
        capacity: Int = 32,
        dedupeCapacity: Int = 1024,
        windowMs: Long = 600_000,
    ) = ControlQueue(capacity, dedupeCapacity, windowMs, clock)

    @Test
    fun `first admit accepts and enqueues`() {
        val queue = queue()
        val admission = queue.admit(request(), instance, 3, trackAvailable = true)

        assertIs<ControlAdmission.Accepted>(admission)
        assertEquals(false, admission.deduplicated)
        assertEquals("play", admission.body.command)
        assertEquals(3L, admission.body.generation)
        assertEquals(1, queue.size())
    }

    @Test
    fun `same requestId with same fingerprint is deduplicated`() {
        val queue = queue()
        queue.admit(request(), instance, 3, trackAvailable = true)
        val second = queue.admit(request(), instance, 3, trackAvailable = true)

        assertIs<ControlAdmission.Accepted>(second)
        assertEquals(true, second.deduplicated)
        assertEquals(1, queue.size(), "去重不得再次入队")
    }

    @Test
    fun `same requestId with different command is rejected`() {
        val queue = queue()
        queue.admit(request(command = ControlCommand.PLAY), instance, 3, trackAvailable = true)
        val second = queue.admit(request(command = ControlCommand.NEXT), instance, 3, trackAvailable = true)

        assertIs<ControlAdmission.Rejected>(second)
        assertEquals(400, second.status)
        assertEquals("invalid_request_id", second.code)
        assertEquals("request_id_reused", (second.detail as Map<*, *>)["reason"])
    }

    @Test
    fun `instance mismatch is rejected`() {
        val queue = queue()
        val result = queue.admit(request(expectedInstanceId = "other"), instance, 3, trackAvailable = true)

        assertIs<ControlAdmission.Rejected>(result)
        assertEquals(409, result.status)
        assertEquals("instance_mismatch", result.code)
    }

    @Test
    fun `seek with stale generation is rejected`() {
        val queue = queue()
        val result = queue.admit(
            request(command = ControlCommand.SEEK, positionMs = 30_000, expectedGeneration = 2),
            currentInstanceId = instance,
            currentGeneration = 3,
            trackAvailable = true,
        )

        assertIs<ControlAdmission.Rejected>(result)
        assertEquals(409, result.status)
        assertEquals("generation_mismatch", result.code)
    }

    @Test
    fun `seek without track is rejected`() {
        val queue = queue()
        val result = queue.admit(
            request(command = ControlCommand.SEEK, positionMs = 30_000, expectedGeneration = 3),
            currentInstanceId = instance,
            currentGeneration = 3,
            trackAvailable = false,
        )

        assertIs<ControlAdmission.Rejected>(result)
        assertEquals(409, result.status)
        assertEquals("no_track", result.code)
    }

    @Test
    fun `seek beyond duration is rejected with maxMs`() {
        val queue = queue()
        val result = queue.admit(
            request(command = ControlCommand.SEEK, positionMs = 300_000, expectedGeneration = 3),
            currentInstanceId = instance,
            currentGeneration = 3,
            trackAvailable = true,
            trackDurationMs = 269_000,
        )

        assertIs<ControlAdmission.Rejected>(result)
        assertEquals("position_out_of_range", result.code)
        assertEquals(269_000L, (result.detail as Map<*, *>)["maxMs"])
    }

    @Test
    fun `queue full rolls back the accepted record`() {
        val queue = queue(capacity = 1)
        assertIs<ControlAdmission.Accepted>(
            queue.admit(request(id = "req-000001"), instance, 3, trackAvailable = true),
        )

        val second = queue.admit(request(id = "req-000002"), instance, 3, trackAvailable = true)
        assertIs<ControlAdmission.Rejected>(second)
        assertEquals(429, second.status)
        assertEquals("queue_full", second.code)

        // 回滚是硬要求：若不回滚，客户端重试会拿到 deduplicated，而命令从未执行
        val retry = queue.admit(request(id = "req-000002"), instance, 3, trackAvailable = true)
        assertIs<ControlAdmission.Rejected>(retry)
        assertEquals("queue_full", retry.code, "失败的入队不得留下已接受记录")
    }

    @Test
    fun `dedupe table full rejects without evicting valid records`() {
        val queue = queue(capacity = 100, dedupeCapacity = 2)
        queue.admit(request(id = "req-000001"), instance, 3, trackAvailable = true)
        queue.admit(request(id = "req-000002"), instance, 3, trackAvailable = true)

        val third = queue.admit(request(id = "req-000003"), instance, 3, trackAvailable = true)

        assertIs<ControlAdmission.Rejected>(third)
        assertEquals(429, third.status)
        assertEquals("dedupe_full", third.code)
        assertEquals(2, queue.dedupeSize(), "容量满时不得提前逐出窗口内的记录")
    }

    @Test
    fun `expired records are evicted and the same id can be reused`() {
        val queue = queue(windowMs = 1_000)
        queue.admit(request(), instance, 3, trackAvailable = true)

        clock.advanceBy(1_001)
        val again = queue.admit(request(), instance, 3, trackAvailable = true)

        assertIs<ControlAdmission.Accepted>(again)
        assertEquals(false, again.deduplicated)
    }

    @Test
    fun `deduplication happens before generation check`() {
        // 第一次 next 会切歌：代次从 3 变 4。重试若先校验代次，会被误判成 generation_mismatch
        val queue = queue()
        queue.admit(request(id = "req-000001", command = ControlCommand.NEXT), instance, 3, trackAvailable = true)

        val retry = queue.admit(request(id = "req-000001", command = ControlCommand.NEXT), instance, 4, trackAvailable = true)

        assertIs<ControlAdmission.Accepted>(retry)
        assertEquals(true, retry.deduplicated, "重试必须命中去重，而不是因代次变化被拒")
    }

    @Test
    fun `clear drops queue and dedupe table`() {
        val queue = queue()
        queue.admit(request(), instance, 3, trackAvailable = true)
        queue.clear()

        assertEquals(0, queue.size())
        assertEquals(0, queue.dedupeSize())
    }
}
