package com.saltbridge.wire

import com.saltbridge.core.LyricsPayload

data class HealthJson(
    val ok: Boolean,
    val protocolVersion: String,
    val authRequired: Boolean,
    val instanceId: String,
    val pluginVersion: String,
    val hostVersion: String,
    val servedAtMs: Long,
)

data class CapabilitiesJson(
    val protocolVersion: String,
    val instanceId: String,
    val servedAtMs: Long,
    val plugin: PluginInfoJson,
    val host: HostInfoJson,
    val permissions: PermissionsJson,
    val features: FeaturesJson,
    val limits: LimitsJson,
    val assumptions: List<String>,
)

data class PluginInfoJson(val id: String, val version: String)

data class HostInfoJson(
    val spwVersion: String,
    val channel: String,
    val compiledApiVersion: String,
)

data class PermissionsJson(val libraryRead: Boolean)

data class ControlFeaturesJson(
    val play: Boolean,
    val pause: Boolean,
    val next: Boolean,
    val previous: Boolean,
    val seek: Boolean,
)

data class FeaturesJson(
    val library: Boolean = false,
    val status: Boolean,
    val lyrics: Boolean,
    val cover: Boolean,
    val control: ControlFeaturesJson,
    val longPoll: Boolean,
    val webSocket: Boolean,
)

data class LimitsJson(
    val maxRequestBytes: Int,
    val maxUrlBytes: Int,
    val controlQueue: Int,
    val dedupeWindowMs: Long,
    val dedupeCapacity: Int,
    val positionStaleAfterMs: Long,
    val inferredAnimationMaxMs: Long,
    val lyricsStaleGraceMs: Long,
    val coverMaxBytes: Long,
    val coverPendingRetryAfterMs: Long,
    val coverPendingTimeoutMs: Long,
    val requestsPerSecond: Int,
    val httpWorkers: Int,
    val httpQueue: Int,
)

data class LyricsResponseJson(
    val instanceId: String,
    val servedAtMs: Long,
    val generation: Long,
    val version: Long,
    val status: String,
    val association: String,
    val lineCount: Int,
    val staleSinceMs: Long?,
    val staleUntilMs: Long?,
    val timesBase: String,
    val timingSanity: String?,
    val lines: List<LyricLineJson>,
    val assumptions: List<String>,
)

data class LyricLineJson(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String?,
    val timing: String,
    val timesValid: Boolean,
    val cells: List<LyricCellJson>,
)

data class LyricCellJson(val startMs: Long, val endMs: Long, val text: String)

data class CoverPendingJson(
    val status: String,
    val generation: Long,
    val retryAfterMs: Long,
)

object LyricsMapper {

    fun map(payload: LyricsPayload, instanceId: String, servedAtMs: Long): LyricsResponseJson {
        var previousStart = Long.MIN_VALUE
        val lines = payload.lines.map { line ->
            val timesValid = line.startMs >= 0 &&
                line.endMs >= line.startMs &&
                line.startMs >= previousStart
            previousStart = line.startMs

            LyricLineJson(
                startMs = line.startMs,
                endMs = line.endMs,
                text = line.text,
                translation = line.translation,
                timing = line.timing,
                timesValid = timesValid,
                cells = line.cells.map { cell ->
                    LyricCellJson(startMs = cell.startMs, endMs = cell.endMs, text = cell.text)
                },
            )
        }

        return LyricsResponseJson(
            instanceId = instanceId,
            servedAtMs = servedAtMs,
            generation = payload.generation,
            version = payload.version,
            status = payload.status.protocolName,
            association = payload.association.protocolName,
            lineCount = lines.size,
            staleSinceMs = payload.staleSinceMs,
            staleUntilMs = payload.staleUntilMs,
            timesBase = "trackStart",
            timingSanity = payload.timingSanity?.protocolName,
            lines = lines,
            assumptions = emptyList(),
        )
    }
}
