package dev.nuaforma.contracts

/** Optional provider facet; absence means unsupported discovery, not absence of fonts. */
interface FontCatalogProvider {
    @Throws(GenerationException::class, java.io.IOException::class)
    fun fontCatalog(context: GenerationContext): FontCatalog
}

enum class FontOrigin { BUNDLED, MOUNTED }

class AvailableFont @JvmOverloads constructor(
    val family: String,
    val face: String,
    val origin: FontOrigin,
    val resource: String? = null,
) {
    init {
        require(family.isNotBlank() && family.length <= 128 && family.none(Char::isISOControl))
        require(face.isNotBlank() && face.length <= 64 && face.none(Char::isISOControl))
        require(resource == null || resource.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
    }
}

/** Deployment catalog only: never fonts uploaded with a request or contained in its template. */
class FontCatalog(fonts: List<AvailableFont>) {
    val fonts: List<AvailableFont> = immutableList(fonts)
    init {
        require(this.fonts.size <= 2048)
        require(this.fonts.map { Triple(it.family, it.face, it.origin) }.distinct().size == this.fonts.size)
    }
}
