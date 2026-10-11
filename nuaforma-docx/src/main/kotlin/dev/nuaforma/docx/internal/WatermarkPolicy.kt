package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.DiagnosticCodes
import dev.nuaforma.contracts.TextWatermark

internal object WatermarkPolicy {
    fun validate(watermark: TextWatermark?) {
        if (watermark == null) return
        if (watermark.text.codePoints().anyMatch { code -> code !in listOf(9, 10, 13) && code !in 0x20..0xD7FF && code !in 0xE000..0xFFFD && code !in 0x10000..0x10FFFF }) {
            DocxFailure.fail(DiagnosticCodes.UNSUPPORTED_OPTION, "Watermark text must contain valid XML characters")
        }
    }
}
