package com.saltbridge.probe

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * M2 最小探针插件：只做启停、环境采集与事件记录。
 *
 * 刻意不实现 HTTP 服务与控制接口，避免在协议冻结前写业务。
 */
class ProbePlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    override fun start() {
        val ok = ProbeController.start(pluginContext)
        runCatching {
            WorkshopApi.ui.toast(
                if (ok) "SaltBridge 探针已启动：logs/probe.log" else "探针启动失败：无法读取数据目录",
                if (ok) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Error
            )
        }
    }

    override fun stop() {
        ProbeController.stop()
    }

    override fun update() {
        ProbeController.update()
    }

    override fun delete() {
        ProbeController.delete()
    }
}
