package com.saltbridge.probe

/**
 * `preference_config.json` 里按钮的入口：必须是 public、static、无参。
 */
object ProbeActions {

    @JvmStatic
    fun exportReport() {
        ProbeController.exportReport()
    }

    @JvmStatic
    fun insertLogMarker() {
        ProbeController.insertLogMarker()
    }

    @JvmStatic
    fun probeHttpNow() {
        ProbeController.probeHttpNow()
    }
}
