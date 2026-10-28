# 更新日志

本项目的所有重要变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.1.0] - 2026-10-28

### 支持

- `/api/v1/health`：健康检查，无需令牌。
- `/api/v1/capabilities`：协议版本与能力声明。
- `/api/v1/status`：当前曲目、播放状态与进度。
- `/api/v1/lyrics`：歌词数据与时间轴。
- `/api/v1/cover`：内嵌封面。
- `/api/v1/control`：控制命令 5 条——`play`、`pause`、`next`、`previous`、`seek`。

### 特性

- 单例状态快照：统一的播放状态数据源。
- 曲目代次管理：曲目切换时递增代次，用于识别过期请求。
- 歌词 stale 过期回查：歌词数据过期时自动向宿主重新查询。
- 封面 `202` pending + 单飞：封面异步就绪，重复请求合并为单次查询。
- 令牌认证：令牌保存在 config.json 中（明文）。
- Host/Origin 校验：限制请求来源，降低本机接口被网页滥用的风险。
- 控制去重：10 分钟窗口 / 1024 容量的 requestId 去重队列，失败时回滚记录。

### 已知限制

- 端口固定为 8737，暂不支持配置。
- `seek` 的代次校验是风险削减而非绝对保证，仍可能在极端时序下作用于新曲目。
- `202` 仅表示命令已受理，不代表命令一定被执行。
- `next` / `previous` 不幂等，依赖 requestId 去重来避免重复执行。

### 依赖

- Salt Player ≥ 1.19.0
- SPW Workshop API 0.1.0-dev23
- NanoHTTPD 2.3.1
- Gson 2.15.0

[0.1.0]: https://github.com/saltbridge/saltbridge/releases/tag/v0.1.0
