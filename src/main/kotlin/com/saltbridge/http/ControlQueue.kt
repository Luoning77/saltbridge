package com.saltbridge.http

import com.saltbridge.core.Clock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 控制命令的入队与去重。
 *
 * 协议 5.6 的处理顺序在这里实现 ③④⑤，并且**必须在同一把锁内**：
 * 查重 → 实例/代次校验 → 写记录 + 入队；入队失败要回滚记录。
 *
 * 去重键是 `(instanceId, requestId)`，不是单独的 `requestId`。
 * 逐出只针对过期记录；容量满时拒绝新命令，绝不提前丢掉窗口内的记录。
 */
class ControlQueue(
    private val capacity: Int = 32,
    private val dedupeCapacity: Int = 1024,
    private val dedupeWindowMs: Long = 600_000,
    private val clock: Clock,
) {

    private data class Record(
        val fingerprint: String,
        val acceptedAtMs: Long,
        val response: ControlAcceptedJson,
    )

    private val lock = Any()

    /** 插入顺序即接受时间顺序，`purgeExpired` 可以遇到第一条未过期就停。 */
    private val accepted = LinkedHashMap<String, Record>()
    private val queue = ArrayBlockingQueue<QueuedControl>(capacity)

    /**
     * @param trackAvailable 当前是否有曲目（`seek` 需要）
     * @param trackDurationMs 已知时长；未知时用 24 小时上限
     */
    fun admit(
        request: ControlRequest,
        currentInstanceId: String,
        currentGeneration: Long,
        trackAvailable: Boolean,
        trackDurationMs: Long? = null,
    ): ControlAdmission = synchronized(lock) {
        val now = clock.nowMs()

        // 过期记录先清，否则已过期的 requestId 仍会被当成重复
        purgeExpired(now)

        // ③ 去重必须早于实例/代次校验：第一次 next 会切歌，先校验代次会把重试误判成 generation_mismatch
        val key = keyOf(currentInstanceId, request.requestId)
        val existing = accepted[key]
        if (existing != null) {
            return@synchronized if (existing.fingerprint == request.fingerprint()) {
                ControlAdmission.Accepted(
                    body = existing.response.copy(deduplicated = true),
                    deduplicated = true,
                )
            } else {
                ControlAdmission.Rejected(
                    status = 400,
                    code = "invalid_request_id",
                    message = "requestId was reused with a different command",
                    detail = mapOf("reason" to "request_id_reused"),
                )
            }
        }

        // ④ 实例与代次
        if (request.expectedInstanceId != currentInstanceId) {
            return@synchronized ControlAdmission.Rejected(
                status = 409,
                code = "instance_mismatch",
                message = "Plugin was restarted",
                detail = mapOf("currentInstanceId" to currentInstanceId),
            )
        }

        val expectedGeneration = request.expectedGeneration
        if (expectedGeneration != null && expectedGeneration != currentGeneration) {
            return@synchronized ControlAdmission.Rejected(
                status = 409,
                code = "generation_mismatch",
                message = "Track changed",
                detail = mapOf("currentGeneration" to currentGeneration),
            )
        }

        if (request.command == ControlCommand.SEEK) {
            if (!trackAvailable) {
                return@synchronized ControlAdmission.Rejected(
                    status = 409,
                    code = "no_track",
                    message = "No current track",
                )
            }
            val position = request.positionMs ?: 0L
            val max = trackDurationMs?.takeIf { it > 0 } ?: MAX_POSITION_MS
            if (position < 0 || position > max) {
                return@synchronized ControlAdmission.Rejected(
                    status = 400,
                    code = "position_out_of_range",
                    message = "Position out of range",
                    detail = mapOf("maxMs" to max),
                )
            }
        }

        // 去重表容量：只逐出过期记录，满了就拒绝（purge 已在入函数时做过）
        if (accepted.size >= dedupeCapacity) {
            return@synchronized ControlAdmission.Rejected(
                status = 429,
                code = "dedupe_full",
                message = "Too many accepted requests",
                detail = mapOf("capacity" to dedupeCapacity, "windowMs" to dedupeWindowMs),
            )
        }

        // ⑤ 记录 + 入队
        val response = ControlAcceptedJson(
            accepted = true,
            requestId = request.requestId,
            command = request.command.wireName,
            deduplicated = false,
            acceptedAtMs = now,
            instanceId = currentInstanceId,
            generation = currentGeneration,
        )
        val queued = QueuedControl(
            requestId = request.requestId,
            command = request.command,
            positionMs = request.positionMs,
            expectedGeneration = request.expectedGeneration,
            acceptedAtMs = now,
        )

        if (!queue.offer(queued)) {
            // 回滚：否则客户端重试会拿到 deduplicated，而命令从未执行
            return@synchronized ControlAdmission.Rejected(
                status = 429,
                code = "queue_full",
                message = "Control queue is full",
                detail = mapOf("capacity" to capacity),
            )
        }

        accepted[key] = Record(request.fingerprint(), now, response)
        ControlAdmission.Accepted(body = response, deduplicated = false)
    }

    /** 执行线程阻塞取命令；`stop()` 会中断它。 */
    fun take(timeoutMs: Long): QueuedControl? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun clear() {
        synchronized(lock) {
            queue.clear()
            accepted.clear()
        }
    }

    fun size(): Int = queue.size

    fun dedupeSize(): Int = synchronized(lock) { accepted.size }

    private fun purgeExpired(now: Long) {
        val iterator = accepted.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.acceptedAtMs < dedupeWindowMs) break
            iterator.remove()
        }
    }

    private fun keyOf(instanceId: String, requestId: String): String =
        "$instanceId\u0000$requestId"

    private companion object {
        /** 时长未知时的位置上界（协议 5.6）。 */
        const val MAX_POSITION_MS = 86_400_000L
    }
}
