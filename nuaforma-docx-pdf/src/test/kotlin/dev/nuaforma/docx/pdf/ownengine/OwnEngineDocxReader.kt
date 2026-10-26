package dev.nuaforma.docx.pdf.ownengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader

/** Preserve the qualified fixture font identities while exercising the production reader. */
internal class OwnEngineDocxReader {
    fun read(bytes: ByteArray) = NativeDocxReader(GenerationContext()) { family, bold, italic ->
        require(family == "Liberation Sans") { "The fixture font family is not supplied" }
        when { bold && italic -> "bolditalic"; bold -> "bold"; italic -> "italic"; else -> "regular" }
    }.read(bytes)
}
