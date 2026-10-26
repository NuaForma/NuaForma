package dev.nuaforma.pdf

import java.util.Collections

enum class PdfReferenceKind { TEXT, PAGE, CURRENT_PAGE, TOTAL_PAGES, SEQUENCE_PAGES }

/** Symbolic content. Resolution never executes source field instructions or trusts their cached text. */
data class PdfReferenceField @JvmOverloads constructor(
    val id: String, val kind: PdfReferenceKind, val style: PdfTextStyle, val target: String? = null,
    val linked: Boolean = kind in setOf(PdfReferenceKind.TEXT, PdfReferenceKind.PAGE), val navigation: PdfTextNavigation? = null,
) : PdfInline {
    init {
        requireSemanticId(id)
        target?.let(::requireSemanticId)
        require((target != null) == (kind in setOf(PdfReferenceKind.TEXT, PdfReferenceKind.PAGE, PdfReferenceKind.SEQUENCE_PAGES))) { "Invalid field target" }
        require(!linked || kind in setOf(PdfReferenceKind.TEXT, PdfReferenceKind.PAGE)) { "Computed counts cannot introduce links" }
    }
}

/** Decimal sequence values; a heading reset uses the neutral zero-based outline level. */
data class PdfSequenceField @JvmOverloads constructor(
    val id: String, val sequence: String, val style: PdfTextStyle, val restart: Int? = null,
    val repeat: Boolean = false, val restartAtHeadingLevel: Int? = null,
    val navigation: PdfTextNavigation? = null,
) : PdfInline {
    init {
        requireSemanticId(id); requireSemanticId(sequence)
        require(restart == null || restart in 0..9_999) { "Invalid sequence restart" }
        require(!repeat || restart == null && restartAtHeadingLevel == null) { "Repeat cannot change sequence policy" }
        require(restartAtHeadingLevel == null || restartAtHeadingLevel in 0..8) { "Invalid sequence heading reset" }
    }
}

/** A source bookmark's actual logical contents; adapters retain the range rather than a field cache. */
class PdfReferenceTarget(val name: String, runs: List<PdfInline>) {
    val runs: List<PdfInline> = Collections.unmodifiableList(ArrayList(runs))
    init {
        requireSemanticId(name)
        require(this.runs.size in 1..1_000 && this.runs.all { it is PdfTextRun || it is PdfReferenceField || it is PdfSequenceField || it is PdfTab }) { "Invalid text reference range" }
    }
}

data class PdfContentsLevel @JvmOverloads constructor(val level: Int, val style: PdfTextStyle, val format: PdfParagraphFormat,
    val spaceBefore: Float = 0f, val spaceAfter: Float = 0f,
) {
    init {
        require(level in 0..8) { "Invalid contents level" }
        require(listOf(spaceBefore, spaceAfter).all { it.isFinite() && it in 0f..14_400f }) { "Invalid contents spacing" }
    }
}

/** A marker paragraph expands to these styled logical heading references before pagination. */
class PdfContents @JvmOverloads constructor(levels: List<PdfContentsLevel>, val linked: Boolean = true, val leader: PdfTabLeader = PdfTabLeader.DOT) {
    val levels: List<PdfContentsLevel> = Collections.unmodifiableList(ArrayList(levels))
    init { require(this.levels.size in 1..9 && this.levels.map { it.level }.distinct().size == this.levels.size) { "Invalid contents level selection" } }
}
