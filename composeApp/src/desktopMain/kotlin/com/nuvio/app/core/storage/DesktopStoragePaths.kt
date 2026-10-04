package com.nuvio.app.core.storage

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

// Pure path resolution keeps tests and future migrations away from official data.
// Importing official data, if offered later, must be an explicit user operation.
internal object DesktopStoragePaths {
    fun data(osName: String, userHome: Path, environment: (String) -> String?): Path {
        val os = osName.lowercase(Locale.ROOT)
        return when {
            os.contains("mac") -> userHome.resolve("Library/Application Support/NuvioEnhanced")
            os.contains("win") -> base(environment("APPDATA"), userHome.resolve("AppData/Roaming")).resolve("NuvioEnhanced")
            else -> base(environment("XDG_CONFIG_HOME"), userHome.resolve(".config")).resolve("nuvio-enhanced")
        }
    }

    fun cache(osName: String, userHome: Path, environment: (String) -> String?): Path {
        val os = osName.lowercase(Locale.ROOT)
        return when {
            os.contains("mac") -> userHome.resolve("Library/Caches/NuvioEnhanced")
            os.contains("win") -> base(environment("LOCALAPPDATA"), userHome.resolve("AppData/Local")).resolve("NuvioEnhanced/Cache")
            else -> base(environment("XDG_CACHE_HOME"), userHome.resolve(".cache")).resolve("nuvio-enhanced")
        }
    }

    private fun base(value: String?, fallback: Path): Path =
        value?.takeIf { it.isNotBlank() }?.let(Paths::get) ?: fallback
}
