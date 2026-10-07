package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class ProfileRepositoryLocalMutationTest {
    @Test fun guestMutationsAreConfirmedPersistedAndRespectTheProfileLimit() = runBlocking {
        // The build helper supplies an isolated APPDATA; never touch an installed account.
        assumeTrue(System.getenv("NUVIO_ENHANCED_ISOLATED_THEME_TEST") == "1")
        val stored=ProfileStorage.loadPayload()
        val anonymous=AuthStorage.loadAnonymousUserId()
        @Suppress("UNCHECKED_CAST")
        val authState=AuthRepository::class.java.getDeclaredField("_state").apply { isAccessible=true }
            .get(AuthRepository) as MutableStateFlow<AuthState>
        val priorAuth=authState.value
        try {
            AuthRepository.signInAnonymously()
            ProfileRepository.clearInMemory()
            assertTrue(ProfileRepository.createProfile("Principal","#E6BD75"))
            assertTrue(ProfileRepository.createProfile("Segundo","#E6BD75"))
            assertTrue(ProfileRepository.pushProfiles(listOf(
                ProfilePushPayload(1,"Principal","#E6BD75"),
                ProfilePushPayload(2,"Segundo","#E6BD75",usesPrimaryPlugins=true),
            )))
            assertTrue(ProfileRepository.updateProfile(2,"Meu perfil","#E6BD75"))
            val oldIdentity=ProfileRepository.state.value.profiles.single { it.profileIndex==2 }.id
            assertTrue(oldIdentity.startsWith("local-"))
            assertTrue(ProfileRepository.state.value.profiles.single { it.profileIndex==2 }.usesPrimaryPlugins)
            val owner=ProfileRepository.state.value.profiles.first().userId
            ProfileRepository.clearInMemory()
            ProfileRepository.ensureLoaded(owner)
            assertEquals("Meu perfil",ProfileRepository.state.value.profiles.single { it.profileIndex==2 }.name)
            for(index in 3..MAX_PROFILES) assertTrue(ProfileRepository.createProfile("Perfil $index","#E6BD75"))
            assertFalse(ProfileRepository.createProfile("Excesso","#E6BD75"))
            assertFalse(ProfileRepository.updateProfile(7,"Inválido","#E6BD75"))
            assertFalse(ProfileRepository.deleteProfile(1))
            assertTrue(ProfileRepository.deleteProfile(2))
            assertFalse(ProfileRepository.deleteProfile(2))
            assertTrue(ProfileRepository.createProfile("Novo segundo","#E6BD75"))
            assertEquals("Novo segundo",ProfileRepository.state.value.profiles.single { it.profileIndex==2 }.name)
            assertNotEquals(oldIdentity,ProfileRepository.state.value.profiles.single { it.profileIndex==2 }.id)
        } finally {
            ProfileRepository.clearInMemory()
            ProfileStorage.savePayload(stored.orEmpty())
            if(anonymous==null)AuthStorage.clearAnonymousUserId() else AuthStorage.saveAnonymousUserId(anonymous)
            authState.value=priorAuth
        }
    }
}
