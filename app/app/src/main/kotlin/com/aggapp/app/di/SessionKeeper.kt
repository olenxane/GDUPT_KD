package com.aggapp.app.di

import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.core.model.PlatformLoginState
import com.aggapp.platform.api.AppLog
import com.aggapp.platform.api.CredentialRefresher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 判活结果：网络等探测失败记为 UNKNOWN，不当作登录失效 */
enum class AliveResult { ALIVE, EXPIRED, UNKNOWN }

/**
 * 登录态保活：逐平台调 checkAlive 发起轻量请求维持会话，
 * 并把平台轮换后的新 Cookie 写回加密存储。前台巡检与后台 Worker 共用。
 */
@Singleton
class SessionKeeper @Inject constructor(
    registry: PlatformRegistry,
    private val session: SessionRepository,
) {
    private val adapters = registry.adapters
    private val mutex = Mutex()

    private val _states = MutableStateFlow<Map<Platform, PlatformLoginState>>(emptyMap())
    val states: StateFlow<Map<Platform, PlatformLoginState>> = _states.asStateFlow()

    @Volatile
    private var lastRunAt = 0L

    /** 距上次巡检不足 minIntervalMs 则跳过；返回各平台结果，跳过时返回 null */
    suspend fun keepAlive(minIntervalMs: Long = 0L): Map<Platform, AliveResult>? = mutex.withLock {
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0 && now - lastRunAt < minIntervalMs) return@withLock null
        lastRunAt = now
        val results = mutableMapOf<Platform, AliveResult>()
        val next = _states.value.toMutableMap()
        for (p in Platform.entries) {
            val cred = session.credentials.value[p]
            if (cred == null) {
                next[p] = PlatformLoginState(p, loggedIn = false)
                continue
            }
            val adapter = adapters[p] ?: continue
            val r = try {
                if (adapter.checkAlive(cred)) AliveResult.ALIVE else AliveResult.EXPIRED
            } catch (_: PlatformError.NeedRelogin) {
                AliveResult.EXPIRED
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Keeper", "${p.label} 判活失败，保留原状态", e)
                AliveResult.UNKNOWN
            }
            results[p] = r
            if (r == AliveResult.ALIVE && adapter is CredentialRefresher) {
                adapter.latestCredential(cred)?.takeIf { it != cred }?.let {
                    session.upsert(it)
                    AppLog.d("Keeper", "${p.label} 会话 Cookie 已轮换并落盘")
                }
            }
            next[p] = when (r) {
                AliveResult.ALIVE -> PlatformLoginState(p, loggedIn = true, lastCheckAt = now)
                AliveResult.EXPIRED -> PlatformLoginState(p, loggedIn = false, needRelogin = true, lastCheckAt = now)
                AliveResult.UNKNOWN -> next[p]?.takeIf { it.loggedIn || it.needRelogin }
                    ?: PlatformLoginState(p, loggedIn = true)
            }
        }
        _states.value = next
        AppLog.d("Keeper", "巡检完成 $results")
        results
    }

    /** 登出/登录后同步单平台状态，不发请求 */
    fun markLoggedOut(p: Platform) {
        _states.value = _states.value + (p to PlatformLoginState(p, loggedIn = false))
    }
}
