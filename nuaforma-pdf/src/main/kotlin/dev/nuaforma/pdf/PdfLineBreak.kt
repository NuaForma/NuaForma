package dev.nuaforma.pdf

/** Start another physical line within the same paragraph, retaining the control's font metrics. */
data class PdfLineBreak(val style: PdfTextStyle) : PdfInline
