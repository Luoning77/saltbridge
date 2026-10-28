# SaltBridge HTTP 协议 v1.0

本文根据 26.1.1 当前实现整理。所有时间、时长、位置均以毫秒表示；JSON 使用 UTF-8。可选字段没有值时通常为 `null`。客户端应忽略未知字段。

## 连接与认证

默认地址为 `http://127.0.0.1:8737/api/v1`。只监听 IPv4 回环地址。端口可在插件数据目录 `config.json` 的 `http.port` 中修改，重启生效。

原生客户端发送 `Authorization: Bearer <token>`。在插件配置界面显示访问令牌后，从数据目录的 `token.txt` 读取。重置令牌后旧令牌立即失效。`health` 免令牌，其他端点需要认证。

`Host` 必须为实际端口对应的 `127.0.0.1:端口`、`localhost:端口` 或 `[::1]:端口`；这不表示服务监听 IPv6。所有含 `Origin` 的请求均被拒绝，不支持浏览器跨域接入。`OPTIONS` 返回 405。

## 实例、代次与时间

- `instanceId` 标识本次启用周期；变化后丢弃旧缓存和旧控制重试。
- `generation` 标识曲目代次；与 `instanceId` 组合使用。同一曲目的元数据补全不一定增加代次，同曲重播也不保证增加。
- `seq` 为实例内状态版本；不能用于跨实例排序。
- `servedAtMs`、`reportedAtMs` 等由服务端时钟产生。客户端应以响应中的时间差计算锚点年龄，再用自己的单调时钟计算收到响应后的耗时，避免比较两台机器的绝对时间。
- 异步响应即使成功也可能在客户端收到时已经过期；显示前再次比较实例和代次。

## 端点

| 路径 | 方法 | 成功响应 |
| --- | --- | --- |
| `/health` | GET | 200：`ok`、`protocolVersion`、`authRequired`、`instanceId`、`pluginVersion`、`hostVersion`、`servedAtMs` |
| `/capabilities` | GET | 200：能力、权限、宿主信息、限制参数 |
| `/status` | GET | 200：播放快照 |
| `/lyrics` | GET | 200：歌词正文 |
| `/cover` | GET | 200：封面二进制；202：加载中 |
| `/library/tracks` | GET | 200：本地曲库分页；202：后台查询中 |
| `/control` | POST | 202：已入队；200：已接受请求的重复提交 |

### capabilities

包含 `protocolVersion`、`instanceId`、`servedAtMs` 以及：

- `plugin`：`id`、`version`。
- `host`：`spwVersion`、`channel`、`compiledApiVersion`。
- `permissions.libraryRead`：曲库读取权限。
- `features`：`status`、`lyrics`、`cover`、`library`，以及 `control` 下的 `play/pause/next/previous/seek` 布尔值。`longPoll` 和 `webSocket` 当前为 false。
- `limits`：实际限制，客户端应读取这些值；默认值见下表。
- `assumptions`：当前为空数组。

| limits 字段 | 默认值 |
| --- | ---: |
| maxRequestBytes / maxUrlBytes | 4096 / 2048 |
| controlQueue / dedupeCapacity | 32 / 1024 |
| dedupeWindowMs | 600000 |
| positionStaleAfterMs / inferredAnimationMaxMs | 3000 / 1500 |
| lyricsStaleGraceMs | 2000 |
| coverMaxBytes | 16777216 |
| coverPendingRetryAfterMs / coverPendingTimeoutMs | 500 / 3000 |
| requestsPerSecond | 50 |
| httpWorkers / httpQueue | 8 / 64 |

限流按服务实例共享。HTTP 工作线程及队列满时会直接关闭新连接，客户端需处理传输错误。

### status

顶层字段：`instanceId`、`protocolVersion`、`seq`、`servedAtMs`、`generation`、`trackStatus`、`track`、`playback`、`lyrics`、`assumptions`。

`trackStatus` 为 `unknown/available/none/permission_denied`。`track` 可为 null；存在时含 `id/title/artist/album/albumArtist/durationMs`。预留字段 `year/genre/trackNumber/discNumber/favorite` 当前为 null。不返回本地文件路径。

`playback` 字段：

| 字段 | 语义 |
| --- | --- |
| state | `unknown/stopped/buffering/playing/paused/ended` |
| isPlaying | true、false 或 null（尚未收到事件） |
| position | `reportedMs`、`reportedAtMs` 可为 null；`source` 为 `none/event` |
| activity | 观察到的位置活动：`unknown/advancing/stationary`，不等于播放状态 |
| activityUpdatedAtMs | 活动采样时间，可为 null |
| animation | `mode` 为 `confirmed/inferred/disabled`；`reason` 说明原因；`maxMs` 限制外推时间 |
| lastEventAtMs | 最近宿主事件时间，可为 null |

`animation.reason` 包括 `is_playing/progress_inferred/paused/buffering/ended/stopped/not_advancing/stale_position/no_position`。

位置是宿主上报的锚点，服务端不外推。`animation.mode=disabled` 或锚点过期时停止插值。可外推时，以 `reportedMs` 加上锚点年龄及收到响应后的本地耗时，并受 `animation.maxMs`、`positionStaleAfterMs` 和已知曲长限制。停止插值时保留已显示位置；同一锚点过期不应造成进度倒退。新锚点明显回退可能来自用户 seek，客户端需允许校正。

### lyrics

可带查询参数 `expectedInstanceId` 和 `expectedGeneration`。代次必须为整数。实例或代次不匹配返回 409；确定无曲目时返回 404。曲目判定、代次校验和歌词正文取自同一份状态。

响应字段：`instanceId/servedAtMs/generation/version/status/association/lineCount/staleSinceMs/staleUntilMs/timesBase/timingSanity/lines/assumptions`。`status` 为 `unknown/available/stale/unavailable`；`association` 为 `none/unconfirmed/associated`，associated 仅表示事件顺序配对，不保证宿主返回的内容身份已被验证。`timesBase` 为 `trackStart`；`timingSanity` 为 `ok/suspect` 或 null。

每行含 `startMs/endMs/text/translation/timing/timesValid/cells`；每个逐字单元含 `startMs/endMs/text`。`translation` 可为 null，`cells` 可为空。`timesValid=false` 时不要依赖该行时间定位。

`status` 快照的 `lyrics` 仅含摘要：`status/association/version/lineCount/staleSinceMs/staleUntilMs/timingSanity/updatedAtMs`。正文由本端点提供。切歌后清空旧歌词；已有歌词收到空发布会进入 stale，短暂保留，回查恢复或到期清除。`unavailable` 不代表歌曲必然没有歌词，也可能是加载失败或暂无数据。

### cover

支持与 lyrics 相同的可选实例和代次参数。需要曲库读取权限及有效曲目 ID。

- 200：按实际 `Content-Type` 返回二进制，含 `X-SaltBridge-Instance-Id`、`X-SaltBridge-Generation`、`Cache-Control: no-store`。
- 202：`{"status":"pending","generation":3,"retryAfterMs":500}`。按建议间隔重试，并为重试设置总预算（默认 3000ms）；超时降级显示。202 不保证在预算内成功。
- 403：无曲库权限；404：无曲目或无可用封面。

### control

请求 `Content-Type: application/json`，例如：

```json
{"requestId":"req-000001","expectedInstanceId":"从 health 获取","command":"seek","positionMs":30000,"expectedGeneration":3}
```

| 字段 | 要求 |
| --- | --- |
| requestId | 必填，8–64 个 ASCII 字母、数字、下划线或连字符 |
| expectedInstanceId | 必填，当前实例 ID |
| command | `play/pause/next/previous/seek` |
| positionMs | 仅 seek 可用且必填；JSON 整数字面量，不能为小数、指数形式或超出 Long 范围；不能为负 |
| expectedGeneration | seek 必填，其他命令可选；整数 |

seek 的最大位置为已知且大于零的曲长，否则为 86400000ms。没有曲目时不能 seek。

接受响应含 `accepted/requestId/command/deduplicated/acceptedAtMs/instanceId/generation`。首次接受为 202；相同 requestId 与相同命令参数重试，在去重窗口内为 200，`deduplicated=true`，不再执行。去重窗口默认 10 分钟；窗口内改变命令、位置或代次并复用 ID 返回 400。同一逻辑请求重试应复用原请求，新的操作应换 ID。实例变化后不要重放旧请求。

202 只代表入队，不能证明宿主已执行；执行前代次变化可能导致命令被丢弃。停用时取消排队命令。队列或去重表满返回 429 并带 `Retry-After: 1`，稍后重试。

## 错误格式

```json
{"error":{"code":"generation_mismatch","message":"Track changed","detail":{"currentGeneration":4},"requestId":null}}
```

`detail` 和 `requestId` 可为 null。按 `code` 判断错误，勿解析英文 message。

| HTTP 状态 | code |
| --- | --- |
| 400 | bad_request、request_id_required、invalid_request_id、instance_required、invalid_command、invalid_position、position_out_of_range、unexpected_parameter、generation_required |
| 401 | unauthorized（带 `WWW-Authenticate: Bearer`） |
| 403 | forbidden_host、forbidden_origin、permission_denied |
| 404 | not_found、no_track、cover_unavailable |
| 405 | method_not_allowed |
| 409 | instance_mismatch、generation_mismatch、no_track（控制端点） |
| 413 | payload_too_large |
| 414 | uri_too_long |
| 415 | unsupported_media_type |
| 429 | rate_limited、queue_full、dedupe_full |
| 500 | internal_error |
| 503 | service_unavailable |

服务停止、过载或网络失败时可能没有 JSON 响应。GET 可有界重试；控制重试必须遵守实例和 requestId 去重规则。


### library/tracks（本地曲库列表）

`GET /api/v1/library/tracks?limit=50&afterId=上一页游标`

需要 Bearer 认证及曲库读取权限。`limit` 默认 50，允许 1–200；首页省略 `afterId`，下一页使用响应的 `nextCursor`，按 URL 查询参数编码。未知参数、空游标和非法页大小返回 400。游标最多 512 个字符。

查询在独立有界后台执行器执行。首次请求一般返回 202，响应含 `instanceId/status=pending/retryAfterMs=500`；客户端按建议间隔重试同一页，建议总等待预算 5 秒，避免无限等待。200 示例：

```json
{
  "instanceId": "当前实例ID",
  "tracks": [
    {"id":"曲库ID","title":"歌曲名","artist":"歌手","album":"专辑","durationMs":240000}
  ],
  "nextCursor": null
}
```

`nextCursor=null` 表示已到末页；空库返回 200、空 `tracks` 和 null 游标。时长未知时为 null。不返回本地文件路径。接口读取 Salt Player 已导入曲库，不扫描未导入文件，也不创建歌单或触发播放。

页面缓存 10 秒，最多 8 页、最多 4 个待处理查询；重复请求合并。需要更新列表时等待缓存到期再从首页读取。各页独立读取宿主曲库，不保证遍历期间跨页快照一致，刷新后从首页重新遍历。插件重启后客户端应丢弃旧请求与旧游标遍历状态。

未授权或宿主查询期间权限被撤销返回 403；查询超时或失败返回 503、`error.code=library_unavailable`，可稍后重试。后台宿主查询等待上限 3 秒，取消等待不保证取消宿主底层数据库查询。能力通过 `/capabilities` 的 `features.library` 声明；旧版插件没有此字段时按不支持处理。
