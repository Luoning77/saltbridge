package com.saltbridge.plugin

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi

/**
 * SaltBridge 插件入口。
 *
 * `start()` 在宿主 UI 线程执行，只做轻量初始化：创建实例、启动线程、返回；
 * 端口绑定、曲库查询等都在各自的执行器里，不阻塞界面。
 */
class SaltBridgePlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    override fun start() {
        val started = CoreRegistry.start(pluginContext)
        toast(
            if (started) "SaltBridge 正在后台启动：logs/bridge.log" else "SaltBridge 启动失败",
            success = started,
        )
    }

    override fun stop() {
        CoreRegistry.stop()
    }

    override fun update() {
        CoreRegistry.onUpdate()
    }

    override fun delete() {
        CoreRegistry.onDelete()
    }

    private fun toast(text: String, success: Boolean) {
        runCatching {
            WorkshopApi.ui.toast(
                text,
                if (success) WorkshopApi.Ui.ToastType.Success else WorkshopApi.Ui.ToastType.Error,
            )
        }
    }
}
