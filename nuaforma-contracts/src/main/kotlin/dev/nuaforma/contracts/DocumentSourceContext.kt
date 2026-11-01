package dev.nuaforma.contracts

/** Development-only source detail. Unmapped checks retain their reason with absent coordinates. */
class DocumentSourceContext(
    val explanation: String,
    val path: String?,
    val construct: String?,
    excerpt: String,
) {
    val excerpt: String = TemplateErrorContext(excerpt, 0).expression

    init {
        require(explanation.length in 1..256 && explanation.none { it.code < 32 })
        require(path == null || path.length in 1..4096 && PATH.matches(path))
        require(construct == null || construct.length in 1..64 && NAME.matches(construct))
    }

    private companion object {
        val PATH = Regex("(?:/[A-Za-z_][A-Za-z0-9_.:-]*\\[[0-9]{1,8}])+")
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")
    }
}
