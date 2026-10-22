package dev.nuaforma.contracts

import java.io.IOException
import java.io.InputStream
import java.util.Properties

data class ContractVersion(val major: Int, val minor: Int) {
    init {
        require(major >= 0 && minor >= 0) { "Contract version components must not be negative" }
    }

    fun accepts(required: ContractVersion): Boolean = major == required.major && minor >= required.minor

    override fun toString(): String = "$major.$minor"

    companion object {
        /** Development baseline, independent of module release versions; no stable release is claimed. */
        @JvmField val CURRENT = ContractVersion(0, 4)

        /** Numeric patch components are accepted but ignored for compatibility. */
        @JvmStatic
        fun parse(value: String): ContractVersion {
            require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?").matches(value)) {
                "Contract version must be major.minor or major.minor.patch"
            }
            val parts = value.split('.')
            val major = parts[0].toIntOrNull()
            val minor = parts[1].toIntOrNull()
            require(major != null && minor != null) { "Contract version components exceed the supported range" }
            return ContractVersion(major, minor)
        }
    }
}

/** Static descriptor, readable without loading or initializing the provider implementation class. */
class ProviderMetadata(
    val id: String,
    val implementationClass: String,
    val releaseVersion: String,
    val requiredContract: ContractVersion,
) {
    init {
        require(Regex("[a-z][a-z0-9.+-]{0,127}").matches(id)) { "Provider identifier must be a lowercase token" }
        require(CLASS_NAME.matches(implementationClass)) { "Invalid provider implementation class name" }
        require(validRelease(releaseVersion)) { "Provider release version must use major.minor.patch SemVer" }
    }

    companion object {
        private const val MAX_METADATA_BYTES = 8192
        private val KEYS = setOf("provider.id", "provider.class", "provider.version", "contract.major", "contract.minor")
        private val CLASS_NAME = Regex(
            "(?:\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*\\.)*" +
                "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*",
        )

        @JvmStatic
        fun resourcePath(implementationClass: String): String {
            require(CLASS_NAME.matches(implementationClass)) { "Invalid provider implementation class name" }
            return "META-INF/nuaforma/providers/$implementationClass.properties"
        }

        /** Reads Java properties, rejects duplicates and unknown keys, and leaves the input stream open. */
        @JvmStatic
        @Throws(IOException::class)
        fun read(input: InputStream): ProviderMetadata {
            val bytes = input.readNBytes(MAX_METADATA_BYTES + 1)
            require(bytes.size <= MAX_METADATA_BYTES) { "Provider metadata exceeds its size limit" }
            val properties = object : Properties() {
                override fun put(key: Any, value: Any): Any? {
                    require(!containsKey(key)) { "Provider metadata contains duplicate keys" }
                    return super.put(key, value)
                }
            }
            properties.load(bytes.inputStream())
            require(properties.stringPropertyNames() == KEYS) { "Provider metadata has missing or unknown keys" }
            fun component(key: String): Int {
                val value = properties.getProperty(key)
                require(Regex("0|[1-9][0-9]*").matches(value)) { "Contract version component must be a non-negative integer" }
                return requireNotNull(value.toIntOrNull()) { "Contract version component exceeds the supported range" }
            }
            return ProviderMetadata(
                properties.getProperty("provider.id"),
                properties.getProperty("provider.class"),
                properties.getProperty("provider.version"),
                ContractVersion(component("contract.major"), component("contract.minor")),
            )
        }

        private fun validRelease(value: String): Boolean {
            val match = Regex(
                "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                    "(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?",
            ).matchEntire(value) ?: return false
            val prerelease = match.groupValues[4]
            val build = match.groupValues[5]
            fun validIdentifiers(part: String): Boolean =
                part.isEmpty() || part.split('.').all { it.isNotEmpty() && Regex("[0-9A-Za-z-]+").matches(it) }
            if (!validIdentifiers(prerelease) || !validIdentifiers(build)) return false
            return prerelease.isEmpty() || prerelease.split('.').none {
                it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')
            }
        }
    }
}
