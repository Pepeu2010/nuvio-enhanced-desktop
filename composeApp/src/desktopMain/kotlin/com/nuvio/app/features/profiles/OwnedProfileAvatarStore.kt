package com.nuvio.app.features.profiles

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal data class ProfileAvatarScope(val accountId: String, val profileIndex: Int, val profileId: String = "") {
    init { require(accountId.length in 1..512 && profileIndex in 1..MAX_PROFILES && profileId.length <= 512) }
}
internal sealed interface LocalProfileAvatar {
    data class Photo(val assetId: String, val imageUri: String) : LocalProfileAvatar
    data class Bundled(val assetId: String) : LocalProfileAvatar
}

/** Durable personal images, separate from media caches and backend profile payloads. */
internal class OwnedProfileAvatarStore(directory: Path) {
    private val root = directory.toAbsolutePath().normalize()
    private val lock = Any()
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val bundlePattern = Regex("openmoji-[a-f0-9]{4,6}")

    fun load(scope: ProfileAvatarScope): LocalProfileAvatar? = synchronized(lock) {
        runCatching {
            val directory = directory(scope, create = false) ?: return@runCatching null
            val file = directory.resolve("avatar.json")
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 16 * 1024) return@runCatching null
            val payload = Json.parseToJsonElement(Files.readString(file)).jsonObject
            if (payload["schemaVersion"]?.jsonPrimitive?.intOrNull != 1) return@runCatching null
            val id = payload["assetId"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            when (payload["kind"]?.jsonPrimitive?.contentOrNull) {
                "photo" -> {
                    if (!hashPattern.matches(id)) return@runCatching null
                    val photo = directory.resolve("$id-512.png")
                    if (!Files.isRegularFile(photo, NOFOLLOW_LINKS) || Files.size(photo) !in 1..2L * 1024 * 1024) return@runCatching null
                    LocalProfileAvatar.Photo(id, photo.toUri().toString())
                }
                "bundle" -> id.takeIf(bundlePattern::matches)?.let(LocalProfileAvatar::Bundled)
                else -> null
            }
        }.getOrNull()
    }

    fun savePhoto(scope: ProfileAvatarScope, variants: Map<Int, ByteArray>): LocalProfileAvatar.Photo = synchronized(lock) {
        require(variants.keys == AvatarRasterPipeline.variantSizes.toSet())
        require(variants.values.all { it.size in 1..2 * 1024 * 1024 })
        val directory = requireNotNull(directory(scope, create = true))
        val previous = load(scope)
        val id = digest(variants.getValue(512))
        try {
            for ((size, bytes) in variants) atomicWrite(directory.resolve("$id-$size.png"), bytes)
            writeManifest(directory, "photo", id)
        } catch (error: Exception) {
            if (previous !is LocalProfileAvatar.Photo || previous.assetId != id) {
                for (size in AvatarRasterPipeline.variantSizes) runCatching { Files.deleteIfExists(directory.resolve("$id-$size.png")) }
            }
            throw error
        }
        removePreviousPhoto(directory, previous, id)
        LocalProfileAvatar.Photo(id, directory.resolve("$id-512.png").toUri().toString())
    }

    fun saveBundled(scope: ProfileAvatarScope, id: String, availableIds: Set<String>): LocalProfileAvatar.Bundled = synchronized(lock) {
        require(bundlePattern.matches(id) && id in availableIds)
        val directory = requireNotNull(directory(scope, create = true))
        val previous = load(scope)
        writeManifest(directory, "bundle", id)
        removePreviousPhoto(directory, previous, null)
        LocalProfileAvatar.Bundled(id)
    }

    fun clear(scope: ProfileAvatarScope) = synchronized(lock) {
        val directory = directory(scope, create = false) ?: return@synchronized
        val previous = load(scope)
        Files.deleteIfExists(directory.resolve("avatar.json"))
        removePreviousPhoto(directory, previous, null)
    }

    private fun directory(scope: ProfileAvatarScope, create: Boolean): Path? {
        if (create) Files.createDirectories(root)
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return null
        val owner = root.resolve(digest(scope.accountId.toByteArray(Charsets.UTF_8)))
        if (Files.exists(owner, NOFOLLOW_LINKS) && !Files.isDirectory(owner, NOFOLLOW_LINKS)) return null
        if (create) Files.createDirectories(owner)
        if (!Files.isDirectory(owner, NOFOLLOW_LINKS) || !owner.toRealPath().startsWith(root.toRealPath())) return null
        val suffix = if (scope.profileId.isBlank()) "" else "-${digest(scope.profileId.toByteArray(Charsets.UTF_8))}"
        val profile = owner.resolve("profile-${scope.profileIndex}$suffix")
        if (Files.exists(profile, NOFOLLOW_LINKS) && !Files.isDirectory(profile, NOFOLLOW_LINKS)) return null
        if (create) Files.createDirectories(profile)
        if (!Files.isDirectory(profile, NOFOLLOW_LINKS) || !profile.toRealPath().startsWith(root.toRealPath())) return null
        return profile
    }

    private fun writeManifest(directory: Path, kind: String, id: String) {
        val payload = buildJsonObject { put("schemaVersion", 1); put("kind", kind); put("assetId", id) }
        atomicWrite(directory.resolve("avatar.json"), payload.toString().toByteArray(Charsets.UTF_8))
    }

    private fun atomicWrite(file: Path, bytes: ByteArray) {
        val pending = Files.createTempFile(file.parent, "avatar-", ".part")
        try {
            Files.write(pending, bytes)
            try { Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(pending) }
    }

    private fun removePreviousPhoto(directory: Path, previous: LocalProfileAvatar?, currentId: String?) {
        if (previous !is LocalProfileAvatar.Photo || previous.assetId == currentId) return
        for (size in AvatarRasterPipeline.variantSizes) {
            val file = directory.resolve("${previous.assetId}-$size.png")
            if (Files.isRegularFile(file, NOFOLLOW_LINKS)) runCatching { Files.deleteIfExists(file) }
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
