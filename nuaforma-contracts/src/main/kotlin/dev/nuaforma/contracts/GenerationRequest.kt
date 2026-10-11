package dev.nuaforma.contracts

import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.util.Collections
import java.util.Locale

class TextWatermark @JvmOverloads constructor(
    val text: String,
    val color: String = "#B0B0B0",
    val opacity: Double = 0.25,
    val rotation: Double = -45.0,
) {
    init {
        require(text.isNotBlank() && text.length <= 4096) { "Watermark text must contain 1 to 4096 characters" }
        require(Regex("#[0-9a-fA-F]{6}").matches(color)) { "Watermark color must be an RGB hex value" }
        require(opacity.isFinite() && opacity in 0.0..1.0) { "Opacity must be between zero and one" }
        require(rotation.isFinite() && rotation in -360.0..360.0) { "Rotation must be between -360 and 360 degrees" }
    }
}

class GenerationOptions @JvmOverloads constructor(
    val watermark: TextWatermark? = null,
    val locale: Locale = Locale.ROOT,
    val timeZone: ZoneId = ZoneId.of("UTC"),
    extensions: Map<String, *> = emptyMap<String, Any?>(),
) {
    val extensions: Map<String, Any?> = StructuredValues.objectCopy(extensions)
}

/** No document streams or template data: safe to assess without consuming the template. */
class GenerationRequirements @JvmOverloads constructor(
    val input: FormatRequest,
    val output: FormatRequest,
    val options: GenerationOptions = GenerationOptions(),
)

/** Caller-supplied factory for a fresh stream. The provider closes streams it opens through this source. */
fun interface ResourceSource {
    @Throws(IOException::class)
    fun openStream(): InputStream
}

/**
 * A one-use template stream remains caller-owned, including after failure or cancellation.
 * Expressions see only data. Resource identifiers never imply filesystem or network lookup.
 */
class GenerationRequest @JvmOverloads constructor(
    val template: InputStream,
    val requirements: GenerationRequirements,
    data: Map<String, *> = emptyMap<String, Any?>(),
    resources: Map<String, ResourceSource> = emptyMap(),
) {
    val data: Map<String, Any?> = StructuredValues.objectCopy(data)
    val resources: Map<String, ResourceSource>

    init {
        require((resources as Map<*, *>).keys.all {
            it is String && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
        }) {
            "Resources need bounded logical identifiers, not paths or URLs"
        }
        require((resources as Map<*, *>).values.none { it == null }) { "Resource sources must not be null" }
        this.resources = Collections.unmodifiableMap(LinkedHashMap(resources))
    }
}
