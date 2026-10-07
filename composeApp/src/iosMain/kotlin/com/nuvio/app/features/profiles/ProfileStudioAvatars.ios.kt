package com.nuvio.app.features.profiles

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal actual object ProfileStudioAvatars {
    actual val revision: StateFlow<Long> = MutableStateFlow(0L)
    actual fun imageUrl(profile: NuvioProfile): String? = null
    actual suspend fun clearDeletedProfile(profile: NuvioProfile) = Unit
}

@Composable
internal actual fun PlatformProfileStudioAvatarEditor(profile: NuvioProfile) = Unit
