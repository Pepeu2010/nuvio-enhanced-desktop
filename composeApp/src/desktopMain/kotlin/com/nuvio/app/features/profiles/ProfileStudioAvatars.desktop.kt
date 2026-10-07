package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

internal data class BundledProfileAvatar(val id: String, val code: String, val category: String,
    val namePtBr: String, val nameEn: String, val sha256: String)

internal actual object ProfileStudioAvatars {
    private val changes = MutableStateFlow(0L)
    actual val revision: StateFlow<Long> = changes.asStateFlow()
    private val store by lazy { OwnedProfileAvatarStore(DesktopStorage.rootDir.resolve("profile-studio-v1/avatars")) }
    private val lock = Any()
    private val references = mutableMapOf<ProfileAvatarScope, LocalProfileAvatar?>()
    private val bundledUrls = mutableMapOf<String, String>()
    val library: List<BundledProfileAvatar> by lazy {
        val bytes = resource("catalog.json")
        val payload = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(payload["schemaVersion"]?.jsonPrimitive?.intOrNull == 1)
        require(payload["sourceCommit"]?.jsonPrimitive?.content == "f9fc506a3f913be9897ab0181d611d4c910a4104")
        payload.getValue("items").jsonArray.take(256).map { value ->
            val item = value.jsonObject
            val code = item.getValue("code").jsonPrimitive.content
            val sha = item.getValue("sha256").jsonPrimitive.content
            require(code.matches(Regex("[A-F0-9]{4,6}")) && sha.matches(Regex("[a-f0-9]{64}")))
            BundledProfileAvatar(item.getValue("id").jsonPrimitive.content, code,
                item.getValue("category").jsonPrimitive.content, item.getValue("namePtBr").jsonPrimitive.content,
                item.getValue("nameEn").jsonPrimitive.content, sha)
        }.also { require(it.map(BundledProfileAvatar::id).distinct().size == it.size) }
    }

    actual fun imageUrl(profile: NuvioProfile): String? {
        val scope = scope(profile) ?: return null
        return runCatching {
            val reference = synchronized(lock) {
                if (!references.containsKey(scope)) references[scope] = store.load(scope)
                references[scope]
            }
            when (reference) {
                is LocalProfileAvatar.Photo -> reference.imageUri.takeIf { Files.isRegularFile(java.nio.file.Paths.get(java.net.URI(it)), NOFOLLOW_LINKS) }
                is LocalProfileAvatar.Bundled -> library.firstOrNull { it.id == reference.assetId }?.let(::bundledImageUrl)
                null -> null
            }
        }.getOrNull()
    }

    fun scope(profile: NuvioProfile, auth: AuthState = AuthRepository.state.value): ProfileAvatarScope? {
        val account = auth as? AuthState.Authenticated ?: return null
        val owner = profile.userId.ifBlank { account.userId }
        if (owner != account.userId) return null
        if (owner.length !in 1..512 || profile.profileIndex !in 1..MAX_PROFILES || profile.id.length > 512) return null
        return ProfileAvatarScope(owner, profile.profileIndex, profile.id)
    }

    fun savePhoto(profile: NuvioProfile, source: AvatarRasterSource, crop: AvatarCrop) {
        val scope = requireNotNull(scope(profile))
        val saved = store.savePhoto(scope, AvatarRasterPipeline.variants(source, crop))
        publish(scope, saved)
    }

    fun saveBundled(profile: NuvioProfile, avatar: BundledProfileAvatar) {
        val scope = requireNotNull(scope(profile))
        bundledImageUrl(avatar)
        publish(scope, store.saveBundled(scope, avatar.id, library.map { it.id }.toSet()))
    }

    fun reset(profile: NuvioProfile) {
        val scope = requireNotNull(scope(profile))
        store.clear(scope)
        publish(scope, null)
    }

    actual suspend fun clearDeletedProfile(profile: NuvioProfile) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val scope = scope(profile) ?: return@withContext
            store.clear(scope)
            publish(scope, null)
        }
    }

    private fun publish(scope: ProfileAvatarScope, reference: LocalProfileAvatar?) {
        synchronized(lock) { references[scope] = reference; changes.value = changes.value + 1 }
    }

    fun bundledImageUrl(avatar: BundledProfileAvatar): String {
        require(library.any { it == avatar })
        synchronized(lock) { bundledUrls[avatar.id] }?.let { cached ->
            if (Files.isRegularFile(java.nio.file.Paths.get(java.net.URI(cached)), NOFOLLOW_LINKS)) return cached
        }
        val folder = DesktopStorage.rootDir.resolve("profile-studio-v1/bundled")
        Files.createDirectories(folder)
        require(Files.isDirectory(folder, NOFOLLOW_LINKS) && folder.toRealPath().startsWith(DesktopStorage.rootDir.toRealPath()))
        val file = folder.resolve("${avatar.code}.svg")
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val valid = Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 262144 && digest(Files.readAllBytes(file)) == avatar.sha256
        if (!valid) {
            val bytes = resource("${avatar.code}.svg")
            require(digest(bytes) == avatar.sha256)
            val pending = Files.createTempFile(folder, "bundled-", ".part")
            try {
                Files.write(pending, bytes)
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(pending) }
        }
        return file.toUri().toString().also { uri -> synchronized(lock) { bundledUrls[avatar.id] = uri } }
    }

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/profile-studio/avatars/$name")) {
        "Bundled avatar resource unavailable"
    }.use { stream -> stream.readNBytes(262145).also { require(it.size <= 262144) } }
}
