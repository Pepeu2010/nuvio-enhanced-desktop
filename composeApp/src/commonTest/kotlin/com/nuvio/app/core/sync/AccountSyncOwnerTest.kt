package com.nuvio.app.core.sync

import com.nuvio.app.core.auth.AuthState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AccountSyncOwnerTest {
    @Test fun ownerRequiresSameAuthenticatedAccountAndProfile() {
        val owner = AccountSyncOwner("account-a", 2)
        assertTrue(owner.matches(AuthState.Authenticated("account-a", null, false), 2))
        assertFalse(owner.matches(AuthState.Authenticated("account-b", null, false), 2))
        assertFalse(owner.matches(AuthState.Authenticated("account-a", null, false), 1))
        assertFalse(owner.matches(AuthState.Authenticated("account-a", null, true), 2))
        assertFalse(owner.matches(AuthState.Unauthenticated, 2))
        assertFalse(owner.matches(AuthState.Loading, 2))
    }
}
