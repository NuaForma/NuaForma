package dev.nuaforma.docx.pdf.internal

import dev.nuaforma.contracts.*

internal interface PdfConverter {
    fun readiness(): CapabilityAssessment
    fun verify(context: GenerationContext)
    fun fontFamilies(files: ConversionFiles, context: GenerationContext): Set<String>
    fun fontCatalog(files: ConversionFiles, context: GenerationContext): List<AvailableFont> = fontFamilies(files, context).sorted().map {
        AvailableFont(it, "regular", FontOrigin.BUNDLED)
    }
    fun convert(files: ConversionFiles, context: GenerationContext)
    fun validate(files: ConversionFiles, context: GenerationContext)
}
