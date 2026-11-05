// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** Identifiers are fingerprints, never raw session cookies. */
interface AccountSessionStore {
    fun credentialId(): String?
    fun cachedUser(): User?
    fun fallbackUser(): User
    fun rejectedCredentialId(): String?
    fun rememberUser(credentialId: String, user: User)
    fun rejectCredential(credentialId: String)
    fun restorationPending(): Boolean
    fun clear()
}

enum class AccountStatus { SIGNED_OUT, CHECKING, READY, RETRY_PENDING, EXPIRED, STORAGE_UNAVAILABLE }

data class AccountState(
    val credentialId: String? = null,
    val user: User? = null,
    val status: AccountStatus = AccountStatus.SIGNED_OUT
) {
    val canUseAccount get() = credentialId != null && status !in setOf(AccountStatus.EXPIRED, AccountStatus.SIGNED_OUT, AccountStatus.STORAGE_UNAVAILABLE)
    val checking get() = status == AccountStatus.CHECKING
    val message get() = when (status) {
        AccountStatus.CHECKING -> "正在同步账号…"
        AccountStatus.RETRY_PENDING -> "账号暂时无法同步，已保留登录状态"
        AccountStatus.EXPIRED -> "平台已确认登录过期，请重新登录"
        AccountStatus.STORAGE_UNAVAILABLE -> "本机登录信息暂时无法读取，请解锁设备后重试"
        else -> null
    }
}

/** Owns account verification on its caller's UI scope. A null result must mean an
 * explicit platform rejection, not a timeout, HTTP error or malformed response.
 * Lifecycle/network events request a bounded verification; nothing polls forever.
 */
class AccountSession(
    private val scope: CoroutineScope,
    private val store: AccountSessionStore,
    private val loadUser: suspend () -> User?,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val foregroundRefreshIntervalMs: Long = 30_000
) {
    private val _state = MutableStateFlow(restoredState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var refreshJob: Job? = null
    private var refreshingId: String? = null
    private var lastVerifiedAt: Long? = null
    private var signingOut = false

    private fun restoredState(): AccountState {
        val id = store.credentialId()
        if (id == null) return AccountState(status = if (store.restorationPending()) AccountStatus.STORAGE_UNAVAILABLE else AccountStatus.SIGNED_OUT)
        if (store.rejectedCredentialId() == id) return AccountState(id, status = AccountStatus.EXPIRED)
        return AccountState(id, store.cachedUser() ?: store.fallbackUser(), AccountStatus.RETRY_PENDING)
    }

    fun canUseAccount(): Boolean {
        if (signingOut) return false
        if (store.credentialId() != _state.value.credentialId || store.restorationPending()) {
            generation++
            refreshJob?.cancel()
            _state.value = restoredState()
        }
        return _state.value.canUseAccount
    }

    fun refresh(force: Boolean = false, restartRunning: Boolean = false, onVerified: (() -> Unit)? = null): Job? {
        if (signingOut) return null
        val id = store.credentialId()
        if (id == null) {
            generation++
            refreshJob?.cancel()
            _state.value = restoredState()
            return null
        }
        if (!force && store.rejectedCredentialId() == id) {
            _state.value = AccountState(id, status = AccountStatus.EXPIRED)
            return null
        }
        refreshJob?.takeIf { !restartRunning && it.isActive && refreshingId == id }?.let { running ->
            if (onVerified != null) {
                val token = generation
                scope.launch {
                    running.join()
                    if (generation == token && store.credentialId() == id && _state.value.status == AccountStatus.READY) onVerified()
                }
            }
            return running
        }
        val before = _state.value
        val verifiedAt = lastVerifiedAt
        if (!force && before.credentialId == id && before.status == AccountStatus.READY && verifiedAt != null && now() - verifiedAt in 0 until foregroundRefreshIntervalMs) return null
        val user = before.user?.takeIf { before.credentialId == id } ?: store.cachedUser() ?: store.fallbackUser()
        val token = ++generation
        refreshJob?.cancel()
        refreshingId = id
        _state.value = AccountState(id, user, AccountStatus.CHECKING)
        return scope.launch {
            try {
                val verified = loadUser()
                ensureActive()
                if (generation != token) return@launch
                if (store.credentialId() != id) { _state.value = restoredState(); return@launch }
                if (verified == null) {
                    persistBestEffort { store.rejectCredential(id) }
                    ensureActive()
                    if (generation != token || store.credentialId() != id) return@launch
                    _state.value = AccountState(id, status = AccountStatus.EXPIRED)
                } else {
                    // A cache write failure must not destroy a valid online login.
                    persistBestEffort { store.rememberUser(id, verified) }
                    ensureActive()
                    if (generation != token || store.credentialId() != id) return@launch
                    lastVerifiedAt = now()
                    _state.value = AccountState(id, verified, AccountStatus.READY)
                    onVerified?.invoke()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == token && store.credentialId() == id) _state.value = AccountState(id, user, AccountStatus.RETRY_PENDING)
            }
        }.also { refreshJob = it }
    }

    private suspend fun persistBestEffort(write: () -> Unit) {
        try { withContext(Dispatchers.IO) { write() } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Keep the verified in-memory decision on disk failure. */ }
    }

    suspend fun logout() {
        generation++
        refreshJob?.cancel()
        lastVerifiedAt = null
        signingOut = true
        _state.value = AccountState()
        try { withContext(NonCancellable + Dispatchers.IO) { store.clear() } }
        finally { signingOut = false }
    }
}

/** Missing/unreadable account data is a transient failure, not evidence of logout. */
object AccountUserResponse {
    fun parse(data: JSONObject): User? {
        when (data.opt("isLogin")) {
            false -> return null
            true -> Unit
            else -> throw IOException("账号信息暂时无法确认，请稍后重试")
        }
        val mid = data.optLong("mid")
        if (mid <= 0) throw IOException("账号信息暂时不完整，请稍后重试")
        return User(mid, data.optString("uname").ifBlank { "哔哩哔哩用户" }, data.optString("face").https(), data.obj("level_info").optInt("current_level"), data.obj("vip").optInt("status") == 1)
    }
}
