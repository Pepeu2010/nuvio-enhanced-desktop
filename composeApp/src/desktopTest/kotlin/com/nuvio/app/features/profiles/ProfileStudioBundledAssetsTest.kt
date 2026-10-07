package com.nuvio.app.features.profiles

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class ProfileStudioBundledAssetsTest {
    @Test fun localPresentationRequiresTheOwningAccountAndValidProfileIdentity() {
        val auth = com.nuvio.app.core.auth.AuthState.Authenticated("owner-A", null, false)
        val profile = NuvioProfile(id = "profile-uuid", userId = "owner-A", profileIndex = 2)
        assertEquals(ProfileAvatarScope("owner-A", 2, "profile-uuid"), ProfileStudioAvatars.scope(profile, auth))
        assertNull(ProfileStudioAvatars.scope(profile.copy(userId = "owner-B"), auth))
        assertNull(ProfileStudioAvatars.scope(profile, com.nuvio.app.core.auth.AuthState.Unauthenticated))
        assertNull(ProfileStudioAvatars.scope(profile, com.nuvio.app.core.auth.AuthState.Loading))
        assertNull(ProfileStudioAvatars.scope(profile.copy(id = "x".repeat(513)), auth))
        assertEquals(ProfileAvatarScope("owner-A", 2), ProfileStudioAvatars.scope(profile.copy(id = "", userId = ""), auth.copy(isAnonymous = true)))
    }

    @Test fun allPackagedArtworkMatchesThePinnedLicensedCatalog() {
        val library = ProfileStudioAvatars.library
        assertEquals(64, library.size)
        assertEquals(setOf("animals", "fantasy", "space", "nature"), library.map { it.category }.toSet())
        for (asset in library) {
            assertEquals("openmoji-${asset.code.lowercase()}", asset.id)
            val bytes = javaClass.getResourceAsStream("/profile-studio/avatars/${asset.code}.svg")!!.use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(asset.sha256, hash)
            val xml = bytes.toString(Charsets.UTF_8)
            assertFalse(Regex("<!DOCTYPE|<!ENTITY|<script|<foreignObject|onload=", RegexOption.IGNORE_CASE).containsMatchIn(xml))
            assertTrue(asset.namePtBr.isNotBlank())
        }
        val license = javaClass.getResourceAsStream("/profile-studio/avatars/LICENSE.txt")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(license.contains("Creative Commons"))
    }

    @Test fun localSelectionsDoNotIntroduceNewFieldsIntoAccountSerialization() {
        val profile = NuvioProfile(userId = "fixture-owner", profileIndex = 2, name = "Casa",
            avatarId = "account-avatar", avatarUrl = "https://example.org/account.png")
        val payload = ProfilePushPayload(profile.profileIndex, profile.name, profile.avatarColorHex,
            avatarId = profile.avatarId, avatarUrl = profile.avatarUrl)
        val encoded = Json.encodeToString(payload)
        assertEquals(payload, Json.decodeFromString<ProfilePushPayload>(encoded))
        assertFalse(encoded.contains("file:")); assertFalse(encoded.contains("openmoji"))
        assertFalse(encoded.contains("profile-studio"))
    }
}
