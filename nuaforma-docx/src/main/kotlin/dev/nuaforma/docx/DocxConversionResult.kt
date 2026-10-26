package dev.nuaforma.docx

import dev.nuaforma.contracts.GenerationResult
import dev.nuaforma.contracts.TextWatermark

/** Filled DOCX and separately resolved decoration for a downstream format adapter. */
class DocxConversionResult internal constructor(
    val generation: GenerationResult,
    val watermark: TextWatermark?,
    val watermarkFontFamily: String?,
)
