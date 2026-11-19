package dev.nuaforma.pdf

/** Explicit physical page boundary within one logical paragraph. */
data class PdfPageBreak(val style: PdfTextStyle) : PdfInline
