package dev.nuaforma.docx.pdf.adapters.nativeengine.emf

/** Header length includes optional strings; offsets determine whether extension fields exist. */
internal object EmfHeader {

    fun validate(header: EmfRecord) {
        val size = header.bytes.size
        val length = header.int(60)
        val description = header.int(64)
        header.require(length in 0..1024 && (length == 0 && description == 0 ||
            length > 0 && description >= 88 && description.toLong() + length * 2 <= size), "Invalid header description range")
        if (length > 0) header.require(header.short(description + length * 2 - 2) == 0, "Unterminated header description")
        val fixed = if (length > 0) description else size
        if (fixed >= 100) {
            val pixels = header.int(88)
            val offset = header.int(92)
            header.require(header.int(96) == 0, "OpenGL header flag is unsupported")
            header.require(pixels == 0 && offset == 0, "Pixel format descriptors are unsupported")
        }
        if (fixed >= 108) header.require(header.int(100) >= 0 && header.int(104) >= 0, "Invalid micrometer dimensions")
    }

}
