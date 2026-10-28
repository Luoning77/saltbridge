package com.saltbridge.http

import java.security.SecureRandom
import java.util.Base64

/** 令牌持久化抽象：生产用宿主配置，测试用内存实现。 */
interface TokenStore {
    fun load(): String?
    fun save(token: String)
}

class MemoryTokenStore(initial: String? = null) : TokenStore {

    @Volatile
    private var value: String? = initial

    override fun load(): String? = value

    override fun save(token: String) {
        value = token
    }
}

object TokenGenerator {

    private const val PREFIX = "sb_"
    private const val TOKEN_BYTES = 32

    private val random = SecureRandom()

    /** `sb_` + 32 字节随机数的 base64url（无 padding，43 字符）。 */
    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * 令牌的读写与惰性生成。
 *
 * 首次启动时若配置里没有合法令牌，就生成并写回；`reset()` 立即生效，旧令牌马上失效。
 */
class TokenProvider(private val store: TokenStore) {

    private val lock = Any()
    private var cached: String? = null

    fun current(): String = synchronized(lock) { cached ?: resolve() }

    fun reset(): String = synchronized(lock) {
        TokenGenerator.newToken().also {
            store.save(it)
            cached = it
        }
    }

    private fun resolve(): String {
        val existing = store.load()?.takeIf(::isWellFormed)
        if (existing != null) {
            cached = existing
            return existing
        }
        return TokenGenerator.newToken().also {
            store.save(it)
            cached = it
        }
    }

    private fun isWellFormed(token: String): Boolean {
        if (!token.startsWith("sb_")) return false
        val body = token.removePrefix("sb_")
        return body.length >= 40 && body.all { ch ->
            ch.isLetterOrDigit() || ch == '-' || ch == '_'
        }
    }
}
