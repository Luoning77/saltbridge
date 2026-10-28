# SaltBridge · 盐桥

为其他应用提供 Salt Player 的播放状态、歌词与控制接口。

SaltBridge 计划基于 Salt Player for Windows 的官方 Workshop API，在本机提供统一的数据与控制服务，供 Minecraft 模组、桌面组件和其他应用接入。

## 项目状态

项目初始化阶段，尚无可安装的插件或已发布的接口。

## 计划能力

- 读取当前歌曲、歌手、专辑、时长与可用的内嵌封面。
- 提供播放状态、播放进度与歌词时间轴；逐字能力取决于原始歌词数据。
- 提供播放、暂停、上一首、下一首与进度跳转控制。
- 通过 HTTP 提供本机接口，后续评估 WebSocket 事件推送。
- 提供协议版本与能力声明，帮助客户端处理版本差异和功能缺失。

服务计划默认仅监听本机，控制接口使用令牌验证。音频由 Salt Player 播放。

## 发布计划

在 GitHub Releases 和 Salt Player Steam 创意工坊发布插件。Minecraft 的 DangoMusic 适配是预期使用案例之一。

## 参考

- [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api)
- [官方插件开发文档](https://saltplayer.com/workshop/)
