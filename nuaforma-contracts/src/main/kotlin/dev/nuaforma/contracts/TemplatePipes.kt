package dev.nuaforma.contracts

import java.io.InputStream
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import java.util.Properties

/** Trusted, thread-safe value conversion; values and results must be JSON-compatible. */
interface TemplatePipe {
    val definition: TemplatePipeDefinition
    @Throws(GenerationException::class)
    fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any?
}

/** Optional facet for metadata from the same value-pipe snapshot used during generation. */
interface TemplatePipeProvider {
    fun templatePipes(): List<TemplatePipeDefinition>
}

class TemplatePipeDefinition @JvmOverloads constructor(
    val name: String,
    val description: String,
    arguments: List<OptionDefinition> = emptyList(),
    val nullBehavior: String = "Passed to the implementation",
) {
    val arguments: List<OptionDefinition> = immutableList(arguments)
    val chainable: Boolean get() = true
    init {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && name !in setOf("number", "date"))
        require(description.isNotBlank() && description.length <= 1024)
        require(nullBehavior.isNotBlank() && nullBehavior.length <= 256)
        require(this.arguments.size <= 16 && this.arguments.map { it.name }.distinct().size == this.arguments.size)
        require(this.arguments.dropWhile { it.required }.none { it.required })
    }
}

/** No request, resource, transport, or implicit root-data access. Cancellation is cooperative. */
class TemplatePipeContext(val locale: Locale, val timeZone: ZoneId, private val generation: GenerationContext) {
    @Throws(GenerationException::class)
    fun checkActive() = generation.checkActive()
}

/** Static metadata checked before an installed implementation is loaded or initialized. */
class TemplatePipeMetadata(val name: String, val implementationClass: String, val releaseVersion: String, val requiredContract: ContractVersion) {
    init {
        TemplatePipeDefinition(name, "Metadata declaration")
        ProviderMetadata("value-pipe", implementationClass, releaseVersion, requiredContract)
    }
    companion object {
        @JvmStatic
        fun resourcePath(implementationClass: String): String {
            ProviderMetadata.resourcePath(implementationClass)
            return "META-INF/nuaforma/pipes/$implementationClass.properties"
        }
        @JvmStatic
        @Throws(IOException::class)
        fun read(input: InputStream): TemplatePipeMetadata {
            val bytes = input.readNBytes(8193)
            require(bytes.size <= 8192)
            val properties = object : Properties() {
                override fun put(key: Any, value: Any): Any? {
                    require(!containsKey(key))
                    return super.put(key, value)
                }
            }
            properties.load(bytes.inputStream())
            require(properties.stringPropertyNames() == setOf("pipe.name", "pipe.class", "pipe.version", "contract.major", "contract.minor"))
            fun component(key: String): Int {
                val value = properties.getProperty(key)
                require(value.matches(Regex("0|[1-9][0-9]*")))
                return requireNotNull(value.toIntOrNull())
            }
            return TemplatePipeMetadata(properties.getProperty("pipe.name"), properties.getProperty("pipe.class"),
                properties.getProperty("pipe.version"), ContractVersion(component("contract.major"), component("contract.minor")))
        }
    }
}
