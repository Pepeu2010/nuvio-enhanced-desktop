package com.nuvio.app.core.sync

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class AccountSyncOwner(val userId: String, val profileId: Int) {
    fun matches(auth: AuthState, activeProfileId: Int): Boolean =
        auth is AuthState.Authenticated && !auth.isAnonymous && auth.userId == userId && activeProfileId == profileId

    suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!matches(AuthRepository.state.value, ProfileRepository.activeProfileId)) {
            throw CancellationException("Account sync owner changed")
        }
    }
}

internal fun accountSyncOwner(profileId: Int = ProfileRepository.activeProfileId): AccountSyncOwner? {
    val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return null
    return AccountSyncOwner(auth.userId, profileId).takeIf { it.matches(auth, ProfileRepository.activeProfileId) }
}
