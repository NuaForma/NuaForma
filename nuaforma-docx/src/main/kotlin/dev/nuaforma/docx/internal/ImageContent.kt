package dev.nuaforma.docx.internal

import dev.nuaforma.contracts.ContentResult

/** Only the bounded decoder creates this result; no document-library or host-object payload. */
internal class ImageContent(bytes: ByteArray, val widthEmu: Long, val heightEmu: Long) : ContentResult {
    private val content = bytes.copyOf()
    fun bytes(): ByteArray = content.copyOf()
}
