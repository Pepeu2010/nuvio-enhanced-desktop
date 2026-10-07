package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileMutationConfirmationTest {
    private val request = ProfilePushPayload(2,"Meu perfil","#E6BD75",usesPrimaryPlugins=true)
    private val profile = NuvioProfile(id="remote-profile",userId="account",profileIndex=2,name=request.name,
        avatarColorHex=request.avatarColorHex,usesPrimaryPlugins=true)

    @Test fun staleResponseOrAccountSwitchCannotConfirmAnEdit() {
        assertFalse(confirmsProfileMutation("account","other",listOf(request),listOf(profile)))
        assertFalse(confirmsProfileMutation("account",null,listOf(request),listOf(profile)))
        assertFalse(confirmsProfileMutation("account","account",listOf(request),listOf(profile.copy(name="Old name"))))
        assertFalse(confirmsProfileMutation("account","account",listOf(request),listOf(profile.copy(userId="other"))))
    }

    @Test fun missingDuplicateAndUnexpectedProfilesFailConfirmation() {
        assertFalse(confirmsProfileMutation("account","account",listOf(request),emptyList()))
        assertFalse(confirmsProfileMutation("account","account",listOf(request),listOf(profile,profile)))
        assertFalse(confirmsProfileMutation("account","account",listOf(request,request),listOf(profile)))
        assertFalse(confirmsProfileMutation("account","account",listOf(request),listOf(profile,profile.copy(profileIndex=3))))
    }

    @Test fun cosmeticAndInheritedSettingsMustSurviveTheRoundTrip() {
        assertTrue(confirmsProfileMutation("account","account",listOf(request),listOf(profile)))
        assertTrue(confirmsProfileMutation("account","account",listOf(request),listOf(profile.copy(userId=""))))
        assertFalse(confirmsProfileMutation("account","account",listOf(request),listOf(profile.copy(usesPrimaryPlugins=false))))
        assertFalse(confirmsProfileMutation("account","account",listOf(request),listOf(profile.copy(avatarUrl="https://example.org/a.png"))))
        assertFalse(confirmsProfileMutation("account","account",listOf(request),listOf(profile.copy(profileBackgroundId="unexpected"))))
        // Backend timestamps and PIN metadata are independent of this cosmetic payload.
        assertTrue(confirmsProfileMutation("account","account",listOf(request),listOf(profile.copy(updatedAt="new",pinEnabled=true))))
    }
}
