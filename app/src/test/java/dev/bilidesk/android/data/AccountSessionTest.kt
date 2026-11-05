// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AccountSessionTest {
    private val alice = User(9, "模拟账号 A", "", 4, false)
    private val bob = User(10, "模拟账号 B", "", 5, true)

    private class MemoryStore(user: User? = null): AccountSessionStore {
        var id: String? = "fixture-account-a"
        var remembered = user
        var profileOwner = id
        var rejected: String? = null
        var unreadable = false
        var failProfileWrite = false
        var failRejectionWrite = false
        var clearCount = 0
        override fun credentialId() = id
        override fun cachedUser() = remembered.takeIf { id == profileOwner }
        override fun fallbackUser() = User(9, "已保存的账号", "", 0, false)
        override fun rejectedCredentialId() = rejected
        override fun rememberUser(credentialId: String, user: User) {
            if (failProfileWrite) throw IOException("Simulated cache write failure")
            if (id == credentialId) { remembered = user; profileOwner = id; rejected = null }
        }
        override fun rejectCredential(credentialId: String) {
            if (failRejectionWrite) throw IOException("Simulated rejection marker write failure")
            if (id == credentialId) rejected = credentialId
        }
        override fun restorationPending() = unreadable
        override fun clear() { id = null; remembered = null; rejected = null; clearCount++ }
    }

    @Test fun rememberedIdentityAppearsBeforeAnyNetworkRequest() = runBlocking {
        var calls = 0
        val session = AccountSession(this, MemoryStore(alice), { calls++; alice })
        assertEquals(alice, session.state.value.user)
        assertTrue(session.canUseAccount())
        assertEquals(0, calls)
    }

    @Test fun offlineColdStartKeepsIdentityAndCredentialsThenReconnects() = runBlocking {
        val store = MemoryStore(alice)
        var online = false
        val session = AccountSession(this, store, { if (!online) throw IOException("offline"); alice.copy(level = 5) })
        session.refresh()!!.join()
        assertEquals(AccountStatus.RETRY_PENDING, session.state.value.status)
        assertEquals(alice, session.state.value.user)
        assertTrue(session.canUseAccount())
        assertEquals(0, store.clearCount)
        assertNull(store.rejected)
        online = true
        session.refresh()!!.join()
        assertEquals(AccountStatus.READY, session.state.value.status)
        assertEquals(5, store.remembered!!.level)
    }

    @Test fun upgradeWithCookiesButNoProfileDoesNotRequireAnotherQrLogin() = runBlocking {
        val store = MemoryStore()
        val session = AccountSession(this, store, { throw IOException("offline") })
        session.refresh()!!.join()
        assertEquals("已保存的账号", session.state.value.user!!.name)
        assertTrue(session.canUseAccount())
        assertEquals("fixture-account-a", store.id)
    }

    @Test fun transportHttpCaptchaAndMalformedResponsesDoNotInvalidateSession() = runBlocking {
        val errors = listOf(IOException("timeout"), ApiException(401, "HTTP unauthorized"), ApiException(403, "gateway"), ApiException(-403, "verification required"), ApiException(412, "limited"), ApiException(500, "server"), IOException("malformed account response"))
        for (error in errors) {
            val store = MemoryStore(alice)
            val session = AccountSession(this, store, { throw error })
            session.refresh()!!.join()
            assertEquals(AccountStatus.RETRY_PENDING, session.state.value.status)
            assertEquals(alice, session.state.value.user)
            assertTrue(session.canUseAccount())
            assertNull(store.rejected)
            assertEquals(0, store.clearCount)
        }
    }

    @Test fun explicitRejectionExpiresSessionAndSurvivesAControllerRestart() = runBlocking {
        val store = MemoryStore(alice)
        val session = AccountSession(this, store, { null })
        session.refresh()!!.join()
        assertEquals(AccountStatus.EXPIRED, session.state.value.status)
        assertNull(session.state.value.user)
        assertFalse(session.canUseAccount())
        assertEquals(store.id, store.rejected)
        var calls = 0
        val restored = AccountSession(this, store, { calls++; alice })
        assertFalse(restored.canUseAccount())
        assertNull(restored.refresh())
        assertEquals(0, calls)
    }

    @Test fun newLoginClearsTheOldRejectionAndVerifiesNewCredentials() = runBlocking {
        val store = MemoryStore(alice)
        val session = AccountSession(this, store, { if (store.id == "fixture-account-a") null else bob })
        session.refresh()!!.join()
        store.id = "fixture-account-b"
        store.rejected = null
        session.refresh(force = true)!!.join()
        assertEquals(bob, session.state.value.user)
        assertEquals(AccountStatus.READY, session.state.value.status)
        assertTrue(session.canUseAccount())
    }

    @Test fun failedExpiryPersistenceStillBlocksTheRejectedAccountInMemory() = runBlocking {
        val store = MemoryStore(alice).apply { failRejectionWrite = true }
        val session = AccountSession(this, store, { null })
        session.refresh()!!.join()
        assertEquals(AccountStatus.EXPIRED, session.state.value.status)
        assertFalse(session.canUseAccount())
        assertNull(session.state.value.user)
    }

    @Test fun concurrentRefreshTriggersShareOneReadAndKeepTheVerifiedCallback() = runBlocking {
        val gate = CompletableDeferred<User?>()
        var calls = 0
        var verified = 0
        val session = AccountSession(this, MemoryStore(alice), { calls++; gate.await() })
        val first = session.refresh()!!
        yield()
        assertSame(first, session.refresh(onVerified = { verified++ }))
        gate.complete(alice)
        first.join()
        yield()
        assertEquals(1, calls)
        assertEquals(1, verified)
    }

    @Test fun logoutPreventsLateUncancellableResultsFromRestoringTheAccount() = runBlocking {
        val gate = CompletableDeferred<User?>()
        val store = MemoryStore(alice)
        val session = AccountSession(this, store, { withContext(NonCancellable) { gate.await() } })
        val first = session.refresh()!!
        yield()
        session.logout()
        gate.complete(alice)
        first.join()
        assertEquals(AccountStatus.SIGNED_OUT, session.state.value.status)
        assertNull(session.state.value.user)
        assertNull(store.id)
        assertNull(store.remembered)
    }

    @Test fun aLateRejectionForThePreviousAccountCannotExpireTheNewAccount() = runBlocking {
        val gate = CompletableDeferred<User?>()
        val store = MemoryStore(alice)
        var calls = 0
        val session = AccountSession(this, store, { if (++calls == 1) withContext(NonCancellable) { gate.await() } else bob })
        val old = session.refresh()!!
        yield()
        store.id = "fixture-account-b"
        val fresh = session.refresh(force = true, restartRunning = true)!!
        fresh.join()
        gate.complete(null)
        old.join()
        assertEquals(bob, session.state.value.user)
        assertEquals(AccountStatus.READY, session.state.value.status)
        assertNull(store.rejected)
    }

    @Test fun reconnectReplacesTheOldBlockedReadWithoutRemovingTheUser() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val session = AccountSession(this, MemoryStore(alice), {
            if (++calls == 1) { started.complete(Unit); awaitCancellation() }
            alice
        })
        val old = session.refresh()!!
        started.await()
        val recovered = session.refresh(restartRunning = true)!!
        recovered.join()
        old.join()
        assertTrue(old.isCancelled)
        assertEquals(2, calls)
        assertEquals(AccountStatus.READY, session.state.value.status)
        assertEquals(alice, session.state.value.user)
    }

    @Test fun repeatedForegroundEventsAreThrottledAfterSuccessfulVerification() = runBlocking {
        var time = 1000L
        var calls = 0
        val session = AccountSession(this, MemoryStore(alice), { calls++; alice }, { time })
        session.refresh()!!.join()
        repeat(10) { assertNull(session.refresh()) }
        assertEquals(1, calls)
        time += 30_000
        session.refresh()!!.join()
        assertEquals(2, calls)
    }

    @Test fun missingCookiesRemainSignedOutAndDoNotTriggerAccountReads() = runBlocking {
        val store = MemoryStore().apply { id = null }
        var calls = 0
        val session = AccountSession(this, store, { calls++; alice })
        assertNull(session.refresh())
        assertFalse(session.canUseAccount())
        assertEquals(AccountStatus.SIGNED_OUT, session.state.value.status)
        assertEquals(0, calls)
    }

    @Test fun unavailableEncryptedStorageIsPreservedAndCanBeRetried() = runBlocking {
        val store = MemoryStore(alice).apply { id = null; unreadable = true }
        val session = AccountSession(this, store, { alice })
        assertEquals(AccountStatus.STORAGE_UNAVAILABLE, session.state.value.status)
        assertNull(session.refresh())
        assertEquals(0, store.clearCount)
        store.id = "fixture-account-a"
        store.unreadable = false
        session.refresh()!!.join()
        assertEquals(AccountStatus.READY, session.state.value.status)
        assertEquals(alice, session.state.value.user)
    }

    @Test fun aProfileCacheWriteFailureDoesNotLogOutAnOnlineUser() = runBlocking {
        val store = MemoryStore().apply { failProfileWrite = true }
        val session = AccountSession(this, store, { alice })
        session.refresh()!!.join()
        assertEquals(AccountStatus.READY, session.state.value.status)
        assertEquals(alice, session.state.value.user)
        assertEquals(0, store.clearCount)
    }

    @Test fun incompleteOrUnreadableNavIsNotAnAnonymousAccount() {
        for (input in listOf("{}", "{\"isLogin\":\"false\"}", "{\"isLogin\":true,\"mid\":0}")) {
            try { AccountUserResponse.parse(JSONObject(input)); fail("Malformed response interpreted as an account result") }
            catch (_: IOException) { }
        }
    }

    @Test fun explicitNavGuestIsNullAndValidAccountParses() {
        assertNull(AccountUserResponse.parse(JSONObject("{\"isLogin\":false}")))
        val user = AccountUserResponse.parse(JSONObject("""{"isLogin":true,"mid":9,"uname":"模拟账号 A","face":"//example.invalid/avatar.png","level_info":{"current_level":4},"vip":{"status":0}}"""))!!
        assertEquals(alice.name, user.name)
        assertEquals(9, user.mid)
        assertEquals("https://example.invalid/avatar.png", user.avatar)
    }
}
