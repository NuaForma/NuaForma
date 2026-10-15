package dev.nuaforma.server.internal

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Immutable startup snapshot of build-validated material; never a live host inventory. */
internal class ShippedSbom private constructor(private val content: ByteArray) {
    val bytes: ByteArray get() = content.copyOf()
    val etag: String = "\"" + MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) } + "\""
    companion object {
        fun packaged(): ShippedSbom = read(checkNotNull(ShippedSbom::class.java.getResourceAsStream("/META-INF/nuaforma/sbom.json")))
        fun configured(value: String?): ShippedSbom {
            if (value == null) return packaged()
            val path = Path.of(value)
            require(path.isAbsolute && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 16 * 1024 * 1024)
            return read(Files.newInputStream(path))
        }
        internal fun read(source: InputStream): ShippedSbom = source.use {
            val bytes = it.readNBytes(16 * 1024 * 1024 + 1)
            require(bytes.size in 2..16 * 1024 * 1024 && bytes.first() == '{'.code.toByte()) { "Invalid packaged SBOM" }
            ShippedSbom(bytes)
        }
    }
}
