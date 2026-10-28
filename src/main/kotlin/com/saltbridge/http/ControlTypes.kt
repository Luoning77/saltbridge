package com.saltbridge.http

/**
 * 控制命令。`wireName` 是对外 JSON 里的值，不要用枚举名。
 */
enum class ControlCommand(val wireName: String) {
    PLAY("play"),
    PAUSE("pause"),
    NEXT("next"),
    PREVIOUS("previous"),
    SEEK("seek"),
    ;

    companion object {
        fun fromWire(value: String): ControlCommand? =
            entries.firstOrNull { it.wireName == value }

        val supported: List<String> = entries.map { it.wireName }
    }
}

/**
 * 已通过**字段级**校验的控制请求。
 *
 * 实例/代次校验与去重不在这里做：它们必须与“记录 + 入队”在同一把锁内完成（协议 5.6 ③④⑤）。
 */
data class ControlRequest(
    val requestId: String,
    val expectedInstanceId: String,
    val command: ControlCommand,
    val positionMs: Long? = null,
    val expectedGeneration: Long? = null,
) {
    /** 同 ID 不同指纹视为复用（`reason=request_id_reused`）。 */
    fun fingerprint(): String =
        "${command.wireName}|${positionMs ?: "-"}|${expectedGeneration ?: "-"}"
}

data class ControlAcceptedJson(
    val accepted: Boolean,
    val requestId: String,
    val command: String,
    val deduplicated: Boolean,
    val acceptedAtMs: Long,
    val instanceId: String,
    val generation: Long,
)

/** 入队结果：Accepted 对应 202/200，Rejected 对应 4xx/429/503。 */
sealed interface ControlAdmission {
    data class Accepted(
        val body: ControlAcceptedJson,
        val deduplicated: Boolean,
    ) : ControlAdmission

    data class Rejected(
        val status: Int,
        val code: String,
        val message: String,
        val detail: Any? = null,
    ) : ControlAdmission
}

/** 出队后的命令；执行线程在调用宿主前还要复查实例与代次。 */
data class QueuedControl(
    val requestId: String,
    val command: ControlCommand,
    val positionMs: Long? = null,
    val expectedGeneration: Long? = null,
    val acceptedAtMs: Long = 0,
)
