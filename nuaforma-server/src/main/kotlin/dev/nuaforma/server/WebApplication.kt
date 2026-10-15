package dev.nuaforma.server

/** Explicit application composition; assets are supplied by the separate development distribution. */
class WebApplication @JvmOverloads constructor(assets: List<Asset>, inventory: ByteArray? = null) {
    private val inventoryContent = inventory?.copyOf()
    val inventory: ByteArray? get() = inventoryContent?.copyOf()
    val assets: List<Asset> = java.util.Collections.unmodifiableList(assets.toList())

    init {
        require(assets.size in 1..16 && assets.map { it.path }.distinct().size == assets.size)
        require(assets.any { it.path == "/playground/" })
        require(assets.sumOf { it.bytes.size.toLong() } <= 2 * 1024 * 1024)
        require(inventoryContent == null || inventoryContent.size in 2..16 * 1024 * 1024)
    }

    class Asset(val path: String, val mediaType: String, bytes: ByteArray) {
        private val content = bytes.copyOf()
        val bytes: ByteArray get() = content.copyOf()
        init {
            require(path == "/playground/" || path.matches(Regex("/playground/[a-z][a-z0-9-]*\\.(css|js|svg|png)")))
            require(mediaType in setOf("text/html", "text/css", "text/javascript", "image/svg+xml", "image/png"))
            require(content.isNotEmpty() && content.size <= 1024 * 1024)
        }
    }
}
