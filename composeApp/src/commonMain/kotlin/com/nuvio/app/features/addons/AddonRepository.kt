package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.diagnostics.redactDiagnosticText
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.sync.accountSyncOwner
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val ADDON_PUSH_DEBOUNCE_MS = 500L

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var currentProfileId: Int = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()
    private var rawSyncSnapshot = JsonArray(emptyList())

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        if (initialized) return
        initialized = true
        currentProfileId = effectiveProfileId
        log.d { "initialize() — loading local addons for profile $currentProfileId" }

        val legacyUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
        val legacyEnabled = loadLocalEnabledStates()
        val fallback = AddonStorage.loadSyncSnapshot(currentProfileId)?.let { json.parseToJsonElement(it).jsonArray }
            ?: JsonArray(legacyUrls.mapIndexed { index, url -> buildJsonObject {
                put("url", url); put("name", ""); put("enabled", legacyEnabled[url] ?: true); put("sort_order", index)
            } })
        rawSyncSnapshot = normalizedAddonSnapshot(AddonSyncCoordinator.recover(currentProfileId, fallback))
        val rows = rawSyncSnapshot.associate { it.jsonObject["url"]!!.jsonPrimitive.content to it.jsonObject }
        val storedUrls = rows.keys.toList()
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        if (storedUrls.isEmpty()) return

        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        _uiState.value = AddonsUiState(
            addons = storedUrls.map { manifestUrl ->
                existingByUrl[manifestUrl].toPendingAddon(
                    manifestUrl = manifestUrl,
                    userSetName = rows[manifestUrl]!!["name"]!!.jsonPrimitive.content.takeIf { it.isNotBlank() },
                    enabled = rows[manifestUrl]!!["enabled"]!!.jsonPrimitive.boolean,
                )
            },
        )

        storedUrls.forEach { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == manifestUrl }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddon(manifestUrl)
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return
        cancelActiveRefreshes()
        currentProfileId = effectiveProfileId
        initialized = false
        _uiState.value = AddonsUiState()
        rawSyncSnapshot = JsonArray(emptyList())
    }

    fun clearLocalState() {
        cancelActiveRefreshes()
        pushJobsByProfile.values.forEach(Job::cancel)
        pushJobsByProfile.clear()
        currentProfileId = 1
        initialized = false
        _uiState.value = AddonsUiState()
        rawSyncSnapshot = JsonArray(emptyList())
    }

    suspend fun pullFromServer(profileId: Int) {
        val owner = accountSyncOwner(profileId) ?: return
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        log.i { "pullFromServer() — profileId=$profileId, initialized=$initialized" }
        runCatching {
            AddonSyncCoordinator.reconcile(owner, effectiveProfileId,
                mayUpload = !isUsingPrimaryAddonsFromSecondaryProfile(),
                stillEffective = { resolveEffectiveProfileId(profileId) == effectiveProfileId }) { snapshot ->
                applySyncedSnapshot(effectiveProfileId, snapshot)
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log.e { "pullFromServer() — FAILED: ${e::class.simpleName}" }
            throw e
        }
    }

    private fun applySyncedSnapshot(profileId: Int, snapshot: JsonArray) {
        currentProfileId = profileId
        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        _uiState.value = AddonsUiState(addons = snapshot.map { item ->
            val row = item.jsonObject
            val url = row["url"]!!.jsonPrimitive.content
            val name = row["name"]!!.jsonPrimitive.content.takeIf { it.isNotBlank() }
            existingByUrl[url]?.copy(userSetName = name).toPendingAddon(url, name, row["enabled"]!!.jsonPrimitive.boolean)
        })
        rawSyncSnapshot = snapshot
        persist(sync = false)
        initialized = true
        _uiState.value.addons.filter { it.enabled && it.manifest == null }.forEach { refreshAddon(it.manifestUrl) }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() ||
                state.addons.any { it.manifest != null } ||
                state.addons.none { it.isRefreshing }
        }
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        val initiatingAuth = AuthRepository.state.value
        val initiatingProfile = ProfileRepository.activeProfileId
        val initiatingBackend = ServerConfigurationRepository.active.value.backendUrl
        log.i { "addAddon() — rawUrl=${redactDiagnosticText(rawUrl)}" }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val manifest = try {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(manifestUrl)
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }

        if (AuthRepository.state.value != initiatingAuth || ProfileRepository.activeProfileId != initiatingProfile ||
            ServerConfigurationRepository.active.value.backendUrl != initiatingBackend) {
            throw CancellationException("Addon installation owner changed")
        }

        _uiState.update { current ->
            current.copy(
                addons = current.addons + ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    isRefreshing = false,
                    errorMessage = null,
                ),
            )
        }
        persist()
        pushToServer()
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        log.i { "removeAddon() — ${redactDiagnosticText(manifestUrl)}" }
        var changed = false
        _uiState.update { current ->
            val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
            changed = updatedAddons.size != current.addons.size
            if (changed) current.copy(addons = updatedAddons) else current
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var changed = false
        _uiState.update { current ->
            val addons = current.addons
            if (
                fromIndex !in addons.indices ||
                toIndex !in addons.indices ||
                fromIndex == toIndex
            ) {
                return@update current
            }

            val reordered = addons.toMutableList()
            val movingAddon = reordered.removeAt(fromIndex)
            reordered.add(toIndex, movingAddon)
            changed = true
            current.copy(addons = reordered)
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var shouldRefresh = false
        var changed = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl != manifestUrl || addon.enabled == enabled) {
                        addon
                    } else {
                        changed = true
                        shouldRefresh = enabled && addon.manifest == null && !addon.isRefreshing
                        addon.copy(enabled = enabled)
                    }
                },
            )
        }
        if (!changed) return
        persist()
        pushToServer()
        if (shouldRefresh) {
            refreshAddon(manifestUrl)
        }
    }

    fun refreshAll() {
        _uiState.value.addons.filter { it.enabled }.distinctBy { it.manifestUrl }.forEach { addon ->
            refreshAddon(
                manifestUrl = addon.manifestUrl,
                forceRefresh = true,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        val existingJob = activeRefreshJobs[manifestUrl]
        if (existingJob?.isActive == true) return

        markRefreshing(manifestUrl)
        var refreshJob: Job? = null
        refreshJob = scope.launch {
            try {
                val result = runCatching {
                    val payload = fetchAddonResponseText(
                        url = manifestUrl,
                        forceRefresh = forceRefresh,
                    )
                    AddonManifestParser.parse(
                        manifestUrl = manifestUrl,
                        payload = payload,
                    )
                }

                _uiState.update { current ->
                    current.copy(
                        addons = current.addons.map { addon ->
                            if (addon.manifestUrl != manifestUrl) {
                                addon
                            } else {
                                result.fold(
                                    onSuccess = { manifest ->
                                        addon.copy(
                                            manifest = manifest,
                                            isRefreshing = false,
                                            errorMessage = null,
                                        )
                                    },
                                    onFailure = { error ->
                                        addon.copy(
                                            isRefreshing = false,
                                            errorMessage = error.message ?: getString(Res.string.addon_load_manifest_failed),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            } finally {
                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                    activeRefreshJobs.remove(manifestUrl)
                }
            }
        }
        activeRefreshJobs[manifestUrl] = refreshJob
    }

    private fun pushToServer() {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val owner = accountSyncOwner() ?: return
        val profileId = currentProfileId
        pushJobsByProfile[profileId]?.cancel()
        var pushJob: Job? = null
        pushJob = scope.launch {
            try {
                delay(ADDON_PUSH_DEBOUNCE_MS)
                owner.requireCurrent()
                if (resolveEffectiveProfileId(owner.profileId) != profileId) return@launch
                AddonSyncCoordinator.reconcile(owner, profileId, mayUpload = true,
                    stillEffective = { resolveEffectiveProfileId(owner.profileId) == profileId }) { snapshot ->
                    applySyncedSnapshot(profileId, snapshot)
                }
                log.d { "pushToServer() — success" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e { "pushToServer() — FAILED: ${error::class.simpleName}" }
            } finally {
                if (pushJobsByProfile[profileId] === pushJob) {
                    pushJobsByProfile.remove(profileId)
                }
            }
        }
        pushJobsByProfile[profileId] = pushJob
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist(sync: Boolean = true) {
        val addons = _uiState.value.addons
        val next = JsonArray(addons.distinctBy { it.manifestUrl }.mapIndexed { index, addon -> buildJsonObject {
            put("url", addon.manifestUrl); put("name", addon.userSetName.orEmpty())
            put("enabled", addon.enabled); put("sort_order", index)
        } })
        if (sync) AddonSyncCoordinator.recordLocal(currentProfileId, rawSyncSnapshot, next)
        AddonStorage.saveSyncSnapshot(currentProfileId, next.toString())
        rawSyncSnapshot = next
        AddonStorage.saveInstalledAddonUrls(
            currentProfileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            currentProfileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
    }

    private fun loadLocalEnabledStates(): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(currentProfileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun cancelActiveRefreshes() {
        activeRefreshJobs.values.forEach(Job::cancel)
        activeRefreshJobs.clear()
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

internal fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    val manifestUrl = if (query.isEmpty()) withSuffix else "$withSuffix?$query"
    return manifestUrl.encodeUnsafeHttpUrlCharacters()
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    val manifestUrl = if (query.isEmpty()) manifestPath else "$manifestPath?$query"
    return manifestUrl.encodeUnsafeHttpUrlCharacters()
}
