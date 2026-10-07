package com.nuvio.app.features.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.nuvio.app.core.auth.AuthRepository
import kotlinx.coroutines.flow.StateFlow

/** Presentation-only local assets. Account profile serialization continues to use its existing fields. */
internal expect object ProfileStudioAvatars {
    val revision: StateFlow<Long>
    fun imageUrl(profile: NuvioProfile): String?
    suspend fun clearDeletedProfile(profile: NuvioProfile)
}

@Composable
internal fun rememberProfileAvatarImageUrl(profile: NuvioProfile, avatar: AvatarCatalogItem?): String? {
    val revision = ProfileStudioAvatars.revision.collectAsState().value
    val account = AuthRepository.state.collectAsState().value
    return remember(profile.id, profile.userId, profile.profileIndex, profile.avatarUrl, avatar, revision, account) {
        profileAvatarImageUrl(profile, avatar)
    }
}

@Composable
internal expect fun PlatformProfileStudioAvatarEditor(profile: NuvioProfile)
