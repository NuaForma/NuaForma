package dev.nuaforma.pdf

import java.net.URI
import java.util.Collections

/** Inert navigation only: rendering never opens a URI or retrieves linked resources. */
sealed interface PdfLink {
    data class External(val uri: String) : PdfLink {
        internal val encodedUri: String
        init {
            require(uri.length in 1..2_048 && uri.none { it.isWhitespace() || it.isISOControl() }) { "Invalid link URI" }
            val parsed = try { URI(uri) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Invalid link URI") }
            require(parsed.scheme?.lowercase() in setOf("https", "http", "mailto")) { "Unsupported link scheme" }
            require(parsed.rawUserInfo == null && (parsed.scheme.equals("mailto", true) || !parsed.host.isNullOrEmpty())) { "Invalid link authority" }
            encodedUri = parsed.toASCIIString()
            require(encodedUri.length <= 2_048) { "Encoded link URI exceeds the limit" }
        }
    }
    data class Internal(val destination: String) : PdfLink {
        init { requireDestinationName(destination) }
    }
}

/** Optional title also creates a flat PDF outline entry at the first painted destination text. */
data class PdfDestination @JvmOverloads constructor(val name: String, val title: String? = null) {
    init {
        requireDestinationName(name)
        require(title == null || title.length in 1..256 && title.none { it.isISOControl() }) { "Invalid bookmark title" }
    }
}

/** Independent of styling and source format; a run's destinations refer to its first visible fragment. */
class PdfTextNavigation @JvmOverloads constructor(val link: PdfLink? = null, destinations: List<PdfDestination> = emptyList()) {
    val destinations: List<PdfDestination> = Collections.unmodifiableList(ArrayList(destinations))
    init {
        require(this.destinations.size <= 32 && this.destinations.map { it.name }.distinct().size == this.destinations.size) { "Invalid run destinations" }
        require(link != null || this.destinations.isNotEmpty()) { "Empty navigation metadata" }
    }
}

private fun requireDestinationName(name: String) {
    require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}"))) { "Invalid destination name" }
}
