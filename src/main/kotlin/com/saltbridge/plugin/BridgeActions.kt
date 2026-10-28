package com.saltbridge.plugin

import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * `preference_config.json` 里按钮的入口：必须是 public、static、无参。
 */
object BridgeActions {

    @JvmStatic
    fun exportSnapshot() {
        if (CoreRegistry.current() == null) {
            toast("SaltBridge 未运行，无法导出", success = false)
            return
        }
        val file = CoreRegistry.exportSnapshot()
        if (file == null) {
            toast("快照写入失败，详见 logs/bridge.log", success = false)
        } else {
            toast("状态快照已写入 reports/state-snapshot.json")
        }
    }

    @JvmStatic
    fun insertLogMarker() {
        if (CoreRegistry.current() == null) {
            toast("SaltBridge 未运行", success = false)
            return
        }
        CoreRegistry.logMarker()
        toast("已写入日志分隔线")
    }

    @JvmStatic
    fun showToken() {
        val core = CoreRegistry.current()
        if (core == null) {
            toast("SaltBridge 未运行", success = false)
            return
        }
        val file = CoreRegistry.exportToken()
        if (file == null) {
            toast("令牌写入失败，详见 logs/bridge.log", success = false)
            return
        }
        val port = CoreRegistry.httpPort()
        // toast 可能被截断，token.txt 才是可靠来源（协议 2.2）
        toast("${core.token()}  ·  http://127.0.0.1:$port")
    }

    @JvmStatic
    fun resetToken() {
        if (CoreRegistry.current() == null) {
            toast("SaltBridge 未运行", success = false)
            return
        }
        if (CoreRegistry.resetToken() == null) {
            toast("令牌重置失败", success = false)
        } else {
            toast("令牌已重置，旧令牌立即失效；点「显示访问令牌」查看")
        }
    }

    private fun toast(text: String, success: Boolean = true) {
        runCatching {
            WorkshopApi.ui.toast(
                text,
                if (success) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Error,
            )
        }
    }
}
