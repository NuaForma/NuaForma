package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.template.TemplatePipes

/** Registered DOCX/PDF provider backed solely by the shared in-process PDF engine. */
class DocxPdfProvider private constructor(delegate: NativeDocxPdfProvider) :
    DocumentProvider by delegate, ProviderReadiness by delegate, FontCatalogProvider by delegate,
    ConformancePolicyProvider by delegate, TemplatePipeProvider by delegate, ContentPipeProvider by delegate {
    @JvmOverloads constructor(configuration: NativePdfConfiguration? = null) :
        this(NativeDocxPdfProvider(configuration ?: NativePdfConfiguration.configured()))

    constructor(configuration: NativePdfConfiguration?, pipes: List<ContentPipe>) :
        this(NativeDocxPdfProvider(configuration ?: NativePdfConfiguration.configured(), pipes))

    constructor(configuration: NativePdfConfiguration?, pipes: List<ContentPipe>, valuePipes: TemplatePipes) :
        this(NativeDocxPdfProvider(configuration ?: NativePdfConfiguration.configured(), pipes, valuePipes))
}
