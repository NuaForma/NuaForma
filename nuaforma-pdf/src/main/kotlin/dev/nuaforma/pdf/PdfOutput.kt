package dev.nuaforma.pdf

enum class PdfOutputProfile(val id: String, internal val archivalPart: Int?) {
    STANDARD("standard", null), PDFA_1B("pdfa-1b", 1), PDFA_2B("pdfa-2b", 2), PDFA_3B("pdfa-3b", 3)
}

data class PdfOutputOptions @JvmOverloads constructor(
    val profile: PdfOutputProfile = PdfOutputProfile.STANDARD,
    val title: String? = null, val author: String? = null,
    val validationEnabled: Boolean = true,
) {
    init {
        require(listOfNotNull(title, author).all { it.length <= 1024 && it.codePoints().allMatch { c -> c >= 32 && c !in 0xD800..0xDFFF && c !in 0xFFFE..0xFFFF } }) { "Invalid PDF metadata" }
    }
}

/** A validator must independently inspect these exact bytes against the explicit profile. */
fun interface PdfConformanceValidator {
    fun validate(bytes: ByteArray, profile: PdfOutputProfile)

    fun validateStructure(bytes: ByteArray, profile: PdfOutputProfile, control: PdfRenderControl) {
        throw PdfRenderException(PdfFailureCode.VALIDATION_UNAVAILABLE, "This validator does not provide independent structural validation")
    }

    fun validate(bytes: ByteArray, profile: PdfOutputProfile, control: PdfRenderControl) {
        control.checkActive()
        validate(bytes, profile)
        control.checkActive()
    }
}
