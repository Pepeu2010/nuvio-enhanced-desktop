package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Owned cache files only. Keys are hashed, writes atomic, reads remain available when writes are disabled. */
internal class BoundedFileCache(
    directory: Path,
    private val quotaBytes: () -> Long,
    private val maxFiles: Int = 512,
    private val canWrite: (Long) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val directory = directory.toAbsolutePath().normalize()
    private val lock = Any()
    private val ownedName = Regex("[a-f0-9]{64}\\.cache")
    companion object { const val MAX_ENTRY_BYTES = 32L * MIB }

    fun get(key: String): ByteArray? = synchronized(lock) {
        runCatching {
            if (Files.isSymbolicLink(directory)) return@runCatching null
            val file = file(key)
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > MAX_ENTRY_BYTES) return@runCatching null
            Files.readAllBytes(file).also { runCatching { Files.setLastModifiedTime(file, FileTime.fromMillis(clock())) } }
        }.getOrNull()
    }

    fun put(key: String, bytes: ByteArray): Boolean = synchronized(lock) {
        val quota = quotaBytes().coerceAtLeast(0)
        if (quota <= 0 || maxFiles <= 0 || bytes.size.toLong() > minOf(quota, MAX_ENTRY_BYTES) || !canWrite(bytes.size.toLong())) return false
        runCatching {
            Files.createDirectories(directory)
            require(!Files.isSymbolicLink(directory))
            val target = file(key)
            val pending = Files.createTempFile(directory, "pending-", ".part")
            try {
                Files.write(pending, bytes)
                runCatching { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    .getOrElse { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING) }
                Files.setLastModifiedTime(target, FileTime.fromMillis(clock()))
            } finally { Files.deleteIfExists(pending) }
            trim(quota)
            Files.exists(target, NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private fun file(key: String): Path {
        require(key.length in 1..8192)
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return directory.resolve("$hash.cache")
    }

    private fun trim(quota: Long) {
        val files = Files.list(directory).use { stream -> stream.filter {
            ownedName.matches(it.fileName.toString()) && Files.isRegularFile(it, NOFOLLOW_LINKS)
        }.toList() }.sortedBy { Files.getLastModifiedTime(it).toMillis() }
        var size = files.sumOf { Files.size(it) }
        var count = files.size
        for (file in files) {
            if (size <= quota && count <= maxFiles) break
            val bytes = Files.size(file)
            if (Files.deleteIfExists(file)) { size -= bytes; count-- }
        }
    }
}
