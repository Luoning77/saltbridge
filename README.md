<div align="center">

<img src="docs/assets/saltbridge-cover.png" alt="SaltBridge 封面图" width="100%">

# SaltBridge · 盐桥

**为桌面应用提供 Salt Player 的本地曲库列表、播放状态、歌词、封面与播放控制接口**

![version](https://img.shields.io/badge/version-26.1.1-blue) ![protocol](https://img.shields.io/badge/protocol-v1.0-green) ![platform](https://img.shields.io/badge/platform-Windows%2011-lightgrey) ![host](https://img.shields.io/badge/SPW-%E2%89%A51.19.0-orange)

[English](README_en.md) ｜ 简体中文

</div>

## 特性

| 功能 | 说明 |
| --- | --- |
| 本地曲库列表 | 分页读取 Salt Player 已导入的本地音乐，默认每页 50 首、最多 200 首 |
| 正在播放 | 读取当前曲目、歌手、专辑、时长、进度和内嵌封面 |
| 歌词时间轴 | 提供逐行歌词；原始数据包含真实逐字时间时提供逐字数据 |
| 播放控制 | 支持播放、暂停、上一首、下一首和跳转进度 |
| 本机访问 | 仅监听 `127.0.0.1`，使用令牌认证 |
| 后台资源管理 | 异步初始化、批量日志与日志轮转；封面缓存和后台任务有上限 |

## 快速开始

从 [Releases](https://github.com/Luoning77/saltbridge/releases) 下载 `.spmod`，在 Salt Player「模组管理 → 本地导入」中安装并启用，授予“曲库读取”权限。

需要源码构建时：

```bash
# 构建（需要 JDK 25 + Gradle 9.8.0）
git clone https://github.com/Luoning77/saltbridge.git
cd saltbridge
./gradlew plugin

# 产物在 build/libs/plugin-com.saltbridge-26.1.1.spmod
# 导入 Salt Player → 模组管理 → 本地导入 → 启用 → 只勾「曲库读取」
```

三步看到第一个效果：

```bash
# 1. 取令牌（在插件界面点「显示访问令牌」，从数据目录复制 token.txt）
# 2. 探活（不需要令牌）
curl http://127.0.0.1:8737/api/v1/health

# 3. 读取当前播放状态
curl -H "Authorization: Bearer sb_你的令牌" http://127.0.0.1:8737/api/v1/status
```

## 安装

### 环境要求

| 依赖 | 最低版本 | 说明 |
|------|----------|------|
| Salt Player for Windows | 1.19.0 | 宿主，需启用 Workshop API |
| JDK（构建时） | 25 | Gradle 工具链要求 |
| Gradle（构建时） | 9.8.0 | Wrapper 自带 |
| 客户端（运行时） | 任意 | 任何能发 HTTP 请求的应用 |

### 安装途径

**方式一：下载 .spmod（推荐）**

从 [Releases](../../releases) 下载 `plugin-com.saltbridge-26.1.1.spmod`，在 Salt Player「模组管理」中本地导入。

**方式二：源码构建**

```bash
# 先发布 Workshop API 到本地 Maven（首次）
cd ../spw-workshop-api
./gradlew publishToMavenLocal

# 国内网络：用阿里云镜像
cd ../saltbridge
./gradlew -I tools/init-cn-mirrors.gradle.kts plugin
```

**方式三：JitPack（备选）**

将 `settings.gradle.kts` 中 `mavenLocal()` 换成 `maven("https://jitpack.io")`，对应改 API 版本。

### 失败排查

| 现象 | 原因与解决 |
|------|-----------|
| `找不到 spw-workshop-api` | 先在 `../spw-workshop-api` 执行 `publishToMavenLocal` |
| `dl.google.com 超时` | 用 `-I tools/init-cn-mirrors.gradle.kts` 走阿里云镜像 |
| 导入 .spmod 报错 | 确认 Salt Player ≥ 1.19.0；看宿主日志中的 `ClassNotFoundException` |
| `/status` 返回 401 | 令牌不对；在插件界面点「显示访问令牌」重新获取 |

## 用法

### HTTP API

| 端点 | 方法 | 令牌 | 说明 |
|------|------|------|------|
| `/api/v1/health` | GET | 免 | 探活、实例 ID、宿主版本 |
| `/api/v1/capabilities` | GET | 要 | 能力清单与限制参数 |
| `/api/v1/status` | GET | 要 | 当前播放快照（状态/位置/歌词摘要） |
| `/api/v1/lyrics` | GET | 要 | 歌词正文（逐行时间轴 + 逐字单元） |
| `/api/v1/cover` | GET | 要 | 封面二进制（`202 pending` 异步加载） |
| `/api/v1/library/tracks` | GET | 要 | 已导入的本地曲库列表（分页） |
| `/api/v1/control` | POST | 要 | 播放控制（5 条命令） |

### 读取本地曲库

```bash
curl -H "Authorization: Bearer sb_你的令牌" \
  "http://127.0.0.1:8737/api/v1/library/tracks?limit=50"
```

首次查询通常返回 `202`，按 `retryAfterMs` 间隔重试同一请求，建议总等待预算 5 秒。成功后返回 `tracks` 和 `nextCursor`；下一页把游标作为 `afterId` 参数传入（需 URL 编码），游标为 `null` 时表示结束。页面缓存 10 秒，各页不是同一时刻的曲库快照。

此接口只列出播放器已导入的音乐，不扫描未导入文件，不返回本地路径，也不创建歌单或点选播放。缺少权限返回 `403`，查询失败返回 `503`。本次功能仅在 SaltBridge 中提供，未修改 Minecraft Mod。

### 控制命令

```bash
# 播放
curl -X POST http://127.0.0.1:8737/api/v1/control \
  -H "Authorization: Bearer sb_你的令牌" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"req-000001","expectedInstanceId":"实例ID","command":"play"}'

# 跳转到 30 秒（seek 必须带 expectedGeneration）
curl -X POST http://127.0.0.1:8737/api/v1/control \
  -H "Authorization: Bearer sb_你的令牌" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"req-000002","expectedInstanceId":"实例ID","command":"seek","positionMs":30000,"expectedGeneration":1}'
```

| 命令 | 说明 | 幂等 |
|------|------|------|
| `play` | 播放 | 是 |
| `pause` | 暂停 | 是 |
| `next` | 下一首 | 否（靠 `requestId` 去重） |
| `previous` | 上一首 | 否 |
| `seek` | 跳到 `positionMs`（毫秒） | 是（同一位置） |

### 协议文档

完整协议（字段表、错误码、客户端实现指南）见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

后台任务、日志轮转与缓存上限见 [性能说明](docs/PERFORMANCE.md)。本地曲库列表支持通过 `/api/v1/library/tracks` 分页读取，需授权曲库读取权限。

## FAQ

**Q1：`/status` 的 `state` 为什么是 `unknown`？**
A：插件启用后宿主不重放播放状态，需要等第一个状态事件到达。这是设计行为，不是 bug。`isPlaying` 独立于 `state`，两者可能部分已知。

**Q2：歌词显示"暂无可用歌词"，但歌明明有歌词？**
A：宿主把"加载中/无歌词/加载失败"都发成空列表，三者无法区分。协议的 `unavailable` 不等于"这首歌没有歌词"。稍等片刻或切换歌曲后回填会自动恢复。

**Q3：封面接口一直返回 `202 pending`？**
A：封面是异步加载的。按 `retryAfterMs`（默认 500 ms）重试，最多 7 次（总预算 3 秒）。仍为 `202` 说明宿主没读到封面，降级显示即可。

**Q4：控制命令返回 `202` 但播放器没反应？**
A：`202` 只表示命令已入队，不保证执行。可能原因：宿主线程忙、`seek` 的代次已变（返回 `409`）、插件正在停止（返回 `503`）。看 `bridge.log` 中的 `control.exec` 或 `control.dropped`。

**Q5：如何改端口？**
A：编辑插件数据目录下的 `config.json`，修改 `http.port`（默认 8737），重启插件生效。配置界面暂无数字输入控件。

## 版本管理

版本号和完整命名规则统一放在 [version.properties](version.properties)。修改其中的 `version` 值即可，构建和发布流程自动读取；发布标签需与文件一致，例如 `v26.1.1`。

## 贡献

欢迎提交 issue 与 PR。提交前请运行 `./gradlew test`确保全绿。

## 许可证

Copyright 2026 Luoning77 and SaltBridge contributors.

本项目采用 [Apache License 2.0](LICENSE)。除另有声明的第三方内容外，本仓库的源码与文档均适用此许可证。允许使用、修改、商用与再分发，具体条件以许可证全文为准。

客户端可自由实现协议（`docs/PROTOCOL.md`）；仅通过 HTTP API 接入 SaltBridge，不要求客户端采用同一许可证。第三方依赖仍适用各自的许可证。

---

> 最近更新：2026-10-28 ｜ 版本 26.1.1 ｜ 协议 v1.0
