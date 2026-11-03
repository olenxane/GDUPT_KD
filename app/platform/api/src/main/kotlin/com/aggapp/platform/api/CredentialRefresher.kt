package com.aggapp.platform.api

import com.aggapp.core.model.Credential

/**
 * 可选能力：平台在请求过程中经 Set-Cookie 轮换会话时，返回合并后的最新凭据供上层落盘。
 * 未轮换返回 null。
 */
interface CredentialRefresher {
    fun latestCredential(cred: Credential): Credential?
}
