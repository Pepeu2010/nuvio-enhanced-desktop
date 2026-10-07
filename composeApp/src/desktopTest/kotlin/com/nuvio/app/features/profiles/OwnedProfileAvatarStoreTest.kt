package com.nuvio.app.features.profiles

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.*

class OwnedProfileAvatarStoreTest {
    @Test fun recreatingAnAccountProfileAtTheSameIndexDoesNotInheritTheOldPhoto() {
        val store = OwnedProfileAvatarStore(Files.createTempDirectory("telumia-avatar-recreated-profile"))
        val old = ProfileAvatarScope("account-A", 2, "original-profile-uuid")
        val recreated = ProfileAvatarScope("account-A", 2, "new-profile-uuid")
        val saved = store.savePhoto(old, variants(Color.RED))
        assertEquals(saved, store.load(old))
        assertNull(store.load(recreated))
        store.clear(old)
        assertNull(store.load(old))
    }

    private fun variants(color: Color): Map<Int, ByteArray> {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { graphics.color = color; graphics.fillRect(0, 0, 128, 128) } finally { graphics.dispose() }
        return AvatarRasterPipeline.variants(AvatarRasterPipeline.fromClipboardImage(image), AvatarCrop())
    }

    @Test fun avatarPersistsAcrossRestartAndNeverLeaksBetweenAccountsOrProfiles() {
        val root = Files.createTempDirectory("telumia-profile-avatars")
        val store = OwnedProfileAvatarStore(root)
        val scope = ProfileAvatarScope("account-A", 1)
        val saved = store.savePhoto(scope, variants(Color.RED))
        assertEquals(saved, OwnedProfileAvatarStore(root).load(scope))
        assertNull(store.load(ProfileAvatarScope("account-B", 1)))
        assertNull(store.load(ProfileAvatarScope("account-A", 2)))
        assertTrue(Paths.get(java.net.URI(saved.imageUri)).startsWith(root))
        assertFailsWith<IllegalArgumentException> { ProfileAvatarScope("account-A", 7) }
    }

    @Test fun replacingAndClearingOnlyRemoveThePreviousOwnedAvatarVariants() {
        val root = Files.createTempDirectory("telumia-avatar-replacement")
        val original = root.resolve("personal-original.png").also { Files.writeString(it, "do not remove") }
        val store = OwnedProfileAvatarStore(root)
        val scope = ProfileAvatarScope("../../untrusted-account", 1)
        val old = store.savePhoto(scope, variants(Color.RED))
        val updated = store.savePhoto(scope, variants(Color.BLUE))
        assertFalse(Files.exists(Paths.get(java.net.URI(old.imageUri))))
        assertTrue(Files.exists(Paths.get(java.net.URI(updated.imageUri))))
        store.clear(scope)
        assertNull(store.load(scope))
        assertFalse(Files.exists(Paths.get(java.net.URI(updated.imageUri))))
        assertEquals("do not remove", Files.readString(original))
    }

    @Test fun bundledSelectionsAreAllowlistedAndUnknownSchemaIsPreserved() {
        val root = Files.createTempDirectory("telumia-avatar-bundle")
        val store = OwnedProfileAvatarStore(root)
        val scope = ProfileAvatarScope("account-A", 1)
        val photo = store.savePhoto(scope, variants(Color.RED))
        val directory = Paths.get(java.net.URI(photo.imageUri)).parent
        assertFailsWith<IllegalArgumentException> { store.saveBundled(scope, "../../outside", setOf("../../outside")) }
        assertFailsWith<IllegalArgumentException> { store.saveBundled(scope, "openmoji-1f98a", emptySet()) }
        val bundle = store.saveBundled(scope, "openmoji-1f98a", setOf("openmoji-1f98a"))
        assertEquals(bundle, OwnedProfileAvatarStore(root).load(scope))
        assertFalse(Files.exists(Paths.get(java.net.URI(photo.imageUri))))
        val manifest = directory.resolve("avatar.json")
        Files.writeString(manifest, "{\"schemaVersion\":999,\"kind\":\"photo\",\"assetId\":\"../../outside\"}")
        assertNull(store.load(scope))
        assertTrue(Files.readString(manifest).contains("999"))
        val future = Files.readAllBytes(manifest)
        assertFailsWith<IllegalArgumentException> { store.clear(scope) }
        assertFailsWith<IllegalArgumentException> { store.savePhoto(scope, variants(Color.BLUE)) }
        assertFailsWith<IllegalArgumentException> { store.saveBundled(scope, "openmoji-1f98a", setOf("openmoji-1f98a")) }
        assertContentEquals(future, Files.readAllBytes(manifest))
    }

    @Test fun failedManifestReplacementRollsBackNewImagesAndKeepsThePreviousReference() {
        val root = Files.createTempDirectory("telumia-avatar-rollback")
        val store = OwnedProfileAvatarStore(root)
        val scope = ProfileAvatarScope("account-A", 1)
        val old = store.savePhoto(scope, variants(Color.RED))
        val directory = Paths.get(java.net.URI(old.imageUri)).parent
        val manifest = directory.resolve("avatar.json")
        val backup = directory.resolve("avatar-backup.json")
        Files.move(manifest, backup)
        Files.createDirectory(manifest)
        val blocker = manifest.resolve("owned-test-blocker").also { Files.writeString(it, "block") }
        assertFails { store.savePhoto(scope, variants(Color.BLUE)) }
        assertTrue(Files.exists(Paths.get(java.net.URI(old.imageUri))))
        assertEquals(4, Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".png") }.count().toInt() })
        Files.delete(blocker); Files.delete(manifest); Files.move(backup, manifest)
        assertEquals(old, store.load(scope))
    }
}
