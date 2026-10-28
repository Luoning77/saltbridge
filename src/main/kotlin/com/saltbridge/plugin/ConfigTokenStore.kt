@file:OptIn(UnstableSpwWorkshopApi::class)

package com.saltbridge.plugin

import com.saltbridge.http.TokenStore
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper

/**
 * 令牌明文存在插件的 `config.json`（键 `http.token`）。
 *
 * 宿主没有安全存储，这是能力的限制，不是实现疏漏；
 * 文档与 UI 都不得宣称“加密保存”（协议 2.4）。
 */
class ConfigTokenStore(private val helper: ConfigHelper) : TokenStore {

    override fun load(): String? = runCatching {
        helper.get(KEY, "")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun save(token: String) {
        helper.set(KEY, token)
        helper.save()
    }

    private companion object {
        const val KEY = "http.token"
    }
}
