package dev.nuaforma.pdf

/** Rectangular text exclusion, resolved by an adapter without source-format types. */
enum class PdfImageWrap { NONE, TOP_BOTTOM, LEFT, RIGHT, LARGEST, BOTH }

/** Physical page x; y is relative to either the page or the associated paragraph. */
class PdfAnchoredImage private constructor(
    val image: PdfImage, val left: Float, val top: Float,
    val anchor: PdfBoxAnchor, val wrap: PdfImageWrap, val distances: PdfMargins,
    val drawingOrder: PdfDrawingOrder?,
    val link: PdfLink.External?,
) {

    @JvmOverloads constructor(image: PdfImage, left: Float, top: Float,
        anchor: PdfBoxAnchor = PdfBoxAnchor.PARAGRAPH, wrap: PdfImageWrap = PdfImageWrap.TOP_BOTTOM,
        distances: PdfMargins = PdfMargins(0f, 0f, 0f, 0f),
    ) : this(image, left, top, anchor, wrap, distances, null, null)

    fun withDrawingOrder(order: PdfDrawingOrder?): PdfAnchoredImage = PdfAnchoredImage(image, left, top, anchor, wrap, distances, order, link)

    /** An inert hyperlink covering a fixed unwrapped picture on the page. */
    fun withLink(link: PdfLink.External?): PdfAnchoredImage =
        PdfAnchoredImage(image, left, top, anchor, wrap, distances, drawingOrder, link)

    fun copy(image: PdfImage = this.image, left: Float = this.left, top: Float = this.top,
        anchor: PdfBoxAnchor = this.anchor, wrap: PdfImageWrap = this.wrap, distances: PdfMargins = this.distances,
    ): PdfAnchoredImage = PdfAnchoredImage(image, left, top, anchor, wrap, distances, drawingOrder, link)

    operator fun component1(): PdfImage = image

    operator fun component2(): Float = left

    operator fun component3(): Float = top

    operator fun component4(): PdfBoxAnchor = anchor

    operator fun component5(): PdfImageWrap = wrap

    operator fun component6(): PdfMargins = distances

    override fun equals(other: Any?): Boolean = other is PdfAnchoredImage && image == other.image && left.toBits() == other.left.toBits() &&
        top.toBits() == other.top.toBits() && anchor == other.anchor && wrap == other.wrap && distances == other.distances && drawingOrder == other.drawingOrder && link == other.link

    override fun hashCode(): Int {
        var value = image.hashCode()
        for (part in listOf(left.hashCode(), top.hashCode(), anchor.hashCode(), wrap.hashCode(), distances.hashCode())) value = 31 * value + part
        drawingOrder?.let { value = 31 * value + it.hashCode() }
        return link?.let { 31 * value + it.hashCode() } ?: value
    }

    override fun toString(): String = "PdfAnchoredImage(image=$image, left=$left, top=$top, anchor=$anchor, wrap=$wrap, distances=$distances" +
        (drawingOrder?.let { ", drawingOrder=$it" } ?: "") + (if (link == null) "" else ", linked=true") + ")"

    init {
        require(link == null || anchor == PdfBoxAnchor.PAGE && wrap == PdfImageWrap.NONE) { "Picture links require fixed unwrapped placement" }
        require(left.isFinite() && top.isFinite() && left in 0f..14_400f && top in -14_400f..14_400f) { "Invalid anchored image position" }
        require(listOf(distances.top, distances.right, distances.bottom, distances.left).all { it <= 14_400f }) { "Invalid image wrapping distance" }
    }
}

/** Fractions removed from each source edge before stretching into the declared physical rectangle. */
data class PdfImageCrop @JvmOverloads constructor(val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f, val left: Float = 0f) {
    init {
        require(listOf(top, right, bottom, left).all { it.isFinite() && it in 0f..<1f } && top + bottom < 1f && left + right < 1f) { "Invalid image crop" }
    }
}
