package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.*

internal interface PdfConverter {
    fun readiness(): CapabilityAssessment
    fun verify(context: GenerationContext)
    fun fontFamilies(files: ConversionFiles, context: GenerationContext): Set<String>
    fun convert(files: ConversionFiles, context: GenerationContext)
    fun validate(files: ConversionFiles, context: GenerationContext)
}
