<div align="center">

<img src="docs/assets/saltbridge-cover.png" alt="SaltBridge cover" width="100%">

# SaltBridge

**Provides local library tracks, playback state, lyrics, cover art, and playback control from Salt Player to desktop applications**

![version](https://img.shields.io/badge/version-26.1.1-blue) ![protocol](https://img.shields.io/badge/protocol-v1.0-green) ![platform](https://img.shields.io/badge/platform-Windows%2011-lightgrey) ![host](https://img.shields.io/badge/SPW-%E2%89%A51.19.0-orange)

English ｜ [简体中文](README.md)

</div>

## Features

| Feature | Description |
| --- | --- |
| Local library | Paginate imported Salt Player tracks: 50 per page by default, up to 200 |
| Now playing | Current track, artist, album, duration, position, and embedded cover |
| Lyrics | Line timing and real word timing when available in the source data |
| Playback controls | Play, pause, previous, next, and seek |
| Local access | Listens on `127.0.0.1` only, with token authentication |
| Resource management | Background initialization, buffered logs with rotation, bounded cover cache and tasks |

## Quick Start

Download a `.spmod` from [Releases](https://github.com/Luoning77/saltbridge/releases), import it in Salt Player’s Mod Manager, enable it, and grant Library Read permission.

To build from source:

```bash
# Build (requires JDK 25 + Gradle 9.8.0)
git clone https://github.com/Luoning77/saltbridge.git
cd saltbridge
./gradlew plugin

# Artifact at build/libs/plugin-com.saltbridge-26.1.1.spmod
# Import into Salt Player → Mod Manager → Local Import → Enable → Check "Library Read"
```

See it work in three steps:

```bash
# 1. Get your token (click "Show Access Token" in the plugin UI, copy token.txt from data dir)
# 2. Health check (no token needed)
curl http://127.0.0.1:8737/api/v1/health

# 3. Read current playback status
curl -H "Authorization: Bearer sb_YOUR_TOKEN" http://127.0.0.1:8737/api/v1/status
```

## Installation

### Requirements

| Dependency | Minimum | Notes |
|------|----------|------|
| Salt Player for Windows | 1.19.0 | Host with Workshop API enabled |
| JDK (build time) | 25 | Required by Gradle toolchain |
| Gradle (build time) | 9.8.0 | Wrapper included |
| Client (runtime) | Any | Anything that can send HTTP requests |

### Installation Options

**Option 1: Download .spmod (recommended)**

Download `plugin-com.saltbridge-26.1.1.spmod` from [Releases](../../releases), import via Salt Player's Mod Manager.

**Option 2: Build from source**

```bash
# Publish Workshop API to local Maven first (one-time)
cd ../spw-workshop-api
./gradlew publishToMavenLocal

# Build SaltBridge (use mirrors in mainland China)
cd ../saltbridge
./gradlew -I tools/init-cn-mirrors.gradle.kts plugin
```

**Option 3: JitPack (fallback)**

Replace `mavenLocal()` with `maven("https://jitpack.io")` in `settings.gradle.kts` and adjust API version.

### Troubleshooting

| Symptom | Cause & Fix |
|------|-----------|
| `spw-workshop-api not found` | Run `publishToMavenLocal` in `../spw-workshop-api` first |
| `dl.google.com timeout` | Use `-I tools/init-cn-mirrors.gradle.kts` for Aliyun mirrors |
| .spmod import error | Ensure Salt Player ≥ 1.19.0; check host log for `ClassNotFoundException` |
| `/status` returns 401 | Wrong token; click "Show Access Token" in plugin UI to get a new one |

## Usage

### HTTP API

| Endpoint | Method | Auth | Description |
|------|------|------|------|
| `/api/v1/health` | GET | No | Liveness, instance ID, host version |
| `/api/v1/capabilities` | GET | Yes | Feature list and limits |
| `/api/v1/status` | GET | Yes | Current playback snapshot (state/position/lyrics summary) |
| `/api/v1/lyrics` | GET | Yes | Lyrics body (line timeline + word cells) |
| `/api/v1/cover` | GET | Yes | Cover art binary (`202 pending` async loading) |
| `/api/v1/library/tracks` | GET | Yes | Imported local library tracks (paginated) |
| `/api/v1/control` | POST | Yes | Playback control (5 commands) |

### Read the Local Library

```bash
curl -H "Authorization: Bearer sb_YOUR_TOKEN" \
  "http://127.0.0.1:8737/api/v1/library/tracks?limit=50"
```

An initial `202` means the query is pending. Retry the same request after `retryAfterMs`, with a suggested total waiting budget of 5 seconds. A successful response contains `tracks` and `nextCursor`. Pass the URL-encoded cursor as `afterId` for the next page; `null` marks the end. Pages are cached for 10 seconds and do not form a single database snapshot.

Only tracks already imported into Salt Player are listed. This endpoint does not scan files, expose local paths, manage playlists, or start playback of a selected track. Missing permission returns `403`; query failures return `503`. This addition is in SaltBridge only; the Minecraft Mod is unchanged.

### Control Commands

```bash
# Play
curl -X POST http://127.0.0.1:8737/api/v1/control \
  -H "Authorization: Bearer sb_YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"req-000001","expectedInstanceId":"INSTANCE_ID","command":"play"}'

# Seek to 30 seconds (seek requires expectedGeneration)
curl -X POST http://127.0.0.1:8737/api/v1/control \
  -H "Authorization: Bearer sb_YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"req-000002","expectedInstanceId":"INSTANCE_ID","command":"seek","positionMs":30000,"expectedGeneration":1}'
```

| Command | Description | Idempotent |
|------|------|------|
| `play` | Play | Yes |
| `pause` | Pause | Yes |
| `next` | Next track | No (dedup via `requestId`) |
| `previous` | Previous track | No |
| `seek` | Seek to `positionMs` (ms) | Yes (same position) |

### Protocol Documentation

Full protocol (field tables, error codes, client implementation guide) in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## FAQ

**Q1: Why does `/status` return `state: "unknown"`?**
A: The host does not replay playback state when the plugin starts; it waits for the first state event. This is by design, not a bug. `isPlaying` is independent of `state` — both may be partially known.

**Q2: Lyrics show "no lyrics available" but the song definitely has lyrics?**
A: The host publishes an empty list for "loading / no lyrics / load failed" alike — the three cases are indistinguishable. `unavailable` does not mean "this song has no lyrics". Wait a moment or switch tracks; backfill will recover automatically.

**Q3: Cover endpoint keeps returning `202 pending`?**
A: Cover loading is async. Retry every `retryAfterMs` (default 500 ms), up to 7 attempts (3-second budget). If still `202`, the host couldn't read the cover — degrade gracefully.

**Q4: Control command returns `202` but the player doesn't react?**
A: `202` means the command was enqueued, not executed. Possible reasons: host thread busy, `seek` generation mismatch (returns `409`), plugin stopping (returns `503`). Check `bridge.log` for `control.exec` or `control.dropped`.

**Q5: How do I change the port?**
A: Edit `config.json` in the plugin data directory, change `http.port` (default 8737), restart the plugin. The config UI has no numeric input control yet.

## Version Management

The current version and naming rules are maintained together in [version.properties](version.properties). Edit its `version` value; builds and releases read this file. Release tags must match it, for example `v26.1.1`.

## Contributing

Issues and PRs welcome. Run `./gradlew test` before submitting.

## License

Copyright 2026 Luoning77 and SaltBridge contributors.

This project is licensed under the [Apache License 2.0](LICENSE). The license applies to the source code and documentation in this repository, except third-party content identified under other terms. Use, modification, commercial use, and redistribution are permitted subject to the full license terms.

Clients may freely implement the protocol (`docs/PROTOCOL.md`). Connecting to SaltBridge solely through its HTTP API does not require a client to adopt the same license. Third-party dependencies remain subject to their own licenses.

---

> Last updated: 2026-10-28 ｜ Version 26.1.1 ｜ Protocol v1.0
