package dev.nuaforma.pdf

/** Per-call execution policy, independent of the hosting application's contracts or input format. */
class PdfRenderControl @JvmOverloads constructor(
    val maxOutputBytes: Long = 16_777_216,
    private val checkpoint: Runnable = Runnable {},
    val maxDecodedImageBytes: Long = 67_108_864,
) {
    init {
        require(maxOutputBytes in 1..134_217_728) { "Invalid PDF output budget" }
        require(maxDecodedImageBytes in 0..268_435_456) { "Invalid PDF decoded-image budget" }
    }

    internal fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw PdfRenderException(PdfFailureCode.CANCELLED, "PDF rendering interrupted")
        checkpoint.run()
    }
}
