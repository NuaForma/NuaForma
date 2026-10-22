package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.internal.OpenTypeMetadata
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class DocxFontsTest {
    private val family = "Libre Barcode 39"
    private val font = javaClass.getResourceAsStream("/fonts/LibreBarcode39-Regular.ttf")!!.use { it.readBytes() }
    private val data = mapOf("reference" to "NF001", "customer" to mapOf("name" to "Ada"))

    private fun requirements(mode: String, declarations: List<Map<String, *>> = listOf(mapOf("family" to family, "resource" to "barcode"))) =
        GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX,
            mapOf("fontMode" to mode, "fonts" to if (mode == "preserve") emptyList<Any>() else declarations)))

    private fun fixture(minimal: Boolean = false): MutableMap<String, ByteArray> = SyntheticDocx.parts().apply {
        val original = getValue("word/document.xml").decodeToString()
        val barcode = """<w:p><w:pPr><w:spacing w:before="0" w:after="0"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="$family" w:hAnsi="$family"/><w:sz w:val="60"/></w:rPr><w:t>*{ref</w:t></w:r><w:r><w:rPr><w:rFonts w:ascii="$family" w:hAnsi="$family"/><w:sz w:val="60"/></w:rPr><w:t>erence}*</w:t></w:r></w:p>"""
        val box = Regex("<v:shape id=\"MarginBox\".*?</v:shape>", RegexOption.DOT_MATCHES_ALL).find(original)!!.value
        val changed = box.replace("width:120pt;height:28pt", "width:180pt;height:42pt")
            .replace("margin-left:-16pt;margin-top:-65pt", "margin-left:-18pt;margin-top:-115pt")
            .replace(Regex("<w:txbxContent>.*?</w:txbxContent>", RegexOption.DOT_MATCHES_ALL), "<w:txbxContent>$barcode</w:txbxContent>")
        this["word/document.xml"] = if (minimal) {
            val prefix = original.substringBefore("<w:body>")
            (prefix + "<w:body><w:p><w:r><w:pict>$changed</w:pict></w:r></w:p>" +
                "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>").toByteArray()
        } else original.replace(box, changed).toByteArray()
        if (minimal) this["word/styles.xml"] = getValue("word/styles.xml").decodeToString().replace("Arial", family).toByteArray()
    }

    private fun generate(parts: Map<String, ByteArray>, mode: String = "preserve", bytes: ByteArray = font,
        declarations: List<Map<String, *>> = listOf(mapOf("family" to family, "resource" to "barcode")),
        source: ResourceSource = ResourceSource { bytes.inputStream() }, context: GenerationContext = GenerationContext()): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), requirements(mode, declarations), data,
            if (mode == "preserve") emptyMap() else mapOf("barcode" to source)), output, context)
        return SyntheticDocx.unzip(output.toByteArray())
    }

    @Test
    fun `preserve does not require installed fonts and retains split placeholder font size and rotated placement`() {
        val original = fixture()
        val result = generate(original)
        val xml = result.getValue("word/document.xml").decodeToString()
        assertFalse(xml.contains("{ref")); assertTrue(xml.contains("NF001"))
        val before = Regex("<v:shape id=\"MarginBox\".*?</v:shape>", RegexOption.DOT_MATCHES_ALL).find(original.getValue("word/document.xml").decodeToString())!!.value
        val after = Regex("<v:shape[^>]*id=\"MarginBox\".*?</v:shape>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.value
        fun geometry(shape: String) = SyntheticDocx.semantic(("<root xmlns:w=\"${SyntheticDocx.W}\" xmlns:v=\"urn:schemas-microsoft-com:vml\">" +
            shape.replace(Regex("<w:txbxContent>.*?</w:txbxContent>", RegexOption.DOT_MATCHES_ALL), "<w:txbxContent/>") + "</root>").toByteArray())
        assertEquals(geometry(before), geometry(after))
        assertEquals("*NF001*", Regex("<w:t[^>]*>(.*?)</w:t>").findAll(after).joinToString("") { it.groupValues[1] })
        Regex("<w:r>.*?</w:r>", RegexOption.DOT_MATCHES_ALL).findAll(after).forEach { run ->
            assertTrue(run.value.contains("w:ascii=\"$family\"")); assertTrue(run.value.contains("w:hAnsi=\"$family\"")); assertTrue(run.value.contains("w:val=\"60\""))
        }
        assertTrue(after.contains("rotation:90")); assertTrue(after.contains("w:val=\"60\"")); assertTrue(after.contains(family))
        assertFalse(result.keys.any { it.endsWith(".odttf") })
    }

    @Test
    fun `validation checks supplied font metadata closes resource and leaves the package unembedded`() {
        val closes = AtomicInteger()
        val result = generate(fixture(true), "validate", source = ResourceSource {
            object : java.io.ByteArrayInputStream(font) { override fun close() { closes.incrementAndGet(); super.close() } }
        })
        assertEquals(1, closes.get()); assertFalse(result.keys.any { it.endsWith(".odttf") })
        val metadata = OpenTypeMetadata.read(font)
        assertTrue(family in metadata.families); assertEquals("regular", metadata.face); assertEquals(0, metadata.embeddingBits)
    }

    @Test
    fun `embedding includes the complete font with unchanged permissions and a valid relationship`() {
        val result = generate(fixture(true), "embed")
        val table = result.getValue("word/fontTable.xml").decodeToString()
        val key = Regex("w:fontKey=\"([^\"]+)\"").find(table)!!.groupValues[1].filter { it.isLetterOrDigit() }
        val mask = key.chunked(2).map { it.toInt(16).toByte() }.reversed()
        val decoded = result.entries.single { it.key.endsWith(".odttf") }.value.clone()
        repeat(32) { decoded[it] = (decoded[it].toInt() xor mask[it % 16].toInt()).toByte() }
        assertArrayEquals(font, decoded)
        assertEquals(0, OpenTypeMetadata.read(decoded).embeddingBits)
        assertTrue(table.contains("embedRegular")); assertTrue(result.getValue("word/_rels/fontTable.xml.rels").decodeToString().contains("fonts/nf-"))
        // Reopening validates the font relationship and package without extracting/installing it.
        DocxDocuments.open(SyntheticDocx.zip(result).inputStream()).use { it.writeTo(ByteArrayOutputStream()) }
    }

    private fun changedPermissions(bits: Int, version: Int = 4): ByteArray = font.clone().also { bytes ->
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort(4).toInt() and 65535
        val index = (0 until count).single { String(bytes, 12 + it * 16, 4, Charsets.US_ASCII) == "OS/2" }
        val offset = buffer.getInt(20 + index * 16)
        buffer.putShort(offset, version.toShort()); buffer.putShort(offset + 8, bits.toShort())
    }

    /** Same-length QA-only names avoid both installed-font lookup and stale viewer font caches. */
    private fun isolatedFont(): ByteArray = font.clone().also { bytes ->
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val tables = (0 until (buffer.getShort(4).toInt() and 65535)).associate { index ->
            String(bytes, 12 + index * 16, 4, Charsets.US_ASCII) to 12 + index * 16
        }
        val name = buffer.getInt(tables.getValue("name") + 8)
        val strings = buffer.getShort(name + 4).toInt() and 65535
        repeat(buffer.getShort(name + 2).toInt() and 65535) { index ->
            val record = name + 6 + index * 12
            val platform = buffer.getShort(record).toInt() and 65535
            val id = buffer.getShort(record + 6).toInt() and 65535
            val length = buffer.getShort(record + 8).toInt() and 65535
            val offset = buffer.getShort(record + 10).toInt() and 65535
            if (platform == 3 && id in setOf(1, 3, 4, 6, 16)) {
                val value = when (id) { 1, 16 -> "NFTestFont639024"; 4 -> "NFTestFont639024 Regular"; 6 -> "NFTestFont6390-Regular"; else -> "NFTest639024".padEnd(length / 2, 'X').take(length / 2) }
                val encoded = value.toByteArray(Charsets.UTF_16BE)
                check(encoded.size == length)
                encoded.copyInto(bytes, name + strings + offset)
            }
        }
        fun checksum(start: Int, length: Int): Long = (0 until length step 4).fold(0L) { sum, offset ->
            var word = 0L
            repeat(4) { index -> word = (word shl 8) or if (offset + index < length) (bytes[start + offset + index].toInt() and 255).toLong() else 0 }
            (sum + word) and 0xFFFFFFFFL
        }
        val head = buffer.getInt(tables.getValue("head") + 8)
        buffer.putInt(head + 8, 0)
        tables.values.forEach { record -> buffer.putInt(record + 4, checksum(buffer.getInt(record + 8), buffer.getInt(record + 12)).toInt()) }
        buffer.putInt(head + 8, ((0xB1B0AFBAL - checksum(0, bytes.size)) and 0xFFFFFFFFL).toInt())
    }

    @Test
    fun `permissions follow versioned OpenType rules without an extra license consent requirement`() {
        for (bits in listOf(0, 8, 0x100, 0x108)) OpenTypeMetadata.read(changedPermissions(bits)).requireEditableEmbedding()
        for (bits in listOf(1, 2, 4, 6, 12, 0x200, 0x208)) assertThrows(GenerationException::class.java) {
            OpenTypeMetadata.read(changedPermissions(bits)).requireEditableEmbedding()
        }
        OpenTypeMetadata.read(changedPermissions(12, 2)).requireEditableEmbedding()
        OpenTypeMetadata.read(changedPermissions(0x208, 1)).requireEditableEmbedding()
        assertThrows(GenerationException::class.java) { OpenTypeMetadata.read(changedPermissions(0x208, 2)).requireEditableEmbedding() }
        // Validation is not embedding and does not reject a restricted-embedding font.
        assertDoesNotThrow { generate(fixture(true), "validate", changedPermissions(2)) }
        for (bits in listOf(2, 4, 0x200)) assertThrows(GenerationException::class.java) { generate(fixture(true), "embed", changedPermissions(bits)) }
        assertEquals(AssessmentStatus.SUPPORTED, DocxProvider().assess(requirements("embed")).status)
    }

    @Test
    fun `missing mismatched malformed fonts and ambiguous theme resolution fail without output`() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(fixture(true)).inputStream(), requirements("validate"), data), output, GenerationContext())
        }
        assertEquals(DiagnosticCodes.INVALID_REQUEST, error.diagnostics.single().code); assertEquals(0, output.size())
        assertThrows(GenerationException::class.java) { generate(fixture(), "validate") }
        assertThrows(GenerationException::class.java) { generate(fixture(true), "embed", declarations = listOf(mapOf("family" to "Wrong family", "resource" to "barcode"))) }
        assertThrows(GenerationException::class.java) { generate(fixture(true), "embed", declarations = listOf(mapOf("family" to family, "face" to "bold", "resource" to "barcode"))) }
        for (size in listOf(0, 11, 100)) assertThrows(GenerationException::class.java) { generate(fixture(true), "embed", font.copyOf(size)) }
        val theme = fixture(true).apply { this["word/styles.xml"] = getValue("word/styles.xml").decodeToString().replace("w:ascii=", "w:asciiTheme=").toByteArray() }
        val unsupported = assertThrows(GenerationException::class.java) { generate(theme, "validate") }
        assertEquals(DiagnosticCodes.UNSUPPORTED_OPTION, unsupported.diagnostics.single().code)
        val noTable = fixture(true).apply { this["word/styles.xml"] = getValue("word/styles.xml").decodeToString().replace("<w:rFonts w:ascii=\"$family\" w:hAnsi=\"$family\"/>", "").toByteArray() }
        assertThrows(GenerationException::class.java) { generate(noTable, "validate") }
    }

    @Test
    fun `font resource limits and cancellation close resources before publication`() {
        val closes = AtomicInteger()
        val source = ResourceSource { object : java.io.ByteArrayInputStream(ByteArray(200000)) { override fun close() { closes.incrementAndGet() } } }
        val limits = GenerationContext(GenerationLimits(maxExpandedBytes = 100000))
        val failure = assertThrows(GenerationException::class.java) { generate(fixture(true), "validate", source = source, context = limits) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, failure.diagnostics.single().code); assertEquals(1, closes.get())
        val context = GenerationContext(cancellation = CancellationToken { true })
        assertEquals(DiagnosticCodes.CANCELLED, assertThrows(GenerationException::class.java) { generate(fixture(true), "validate", context = context) }.diagnostics.single().code)
    }

    @Test
    fun `font options reject invalid types duplicate faces and extra consent fields`() {
        val provider = DocxProvider()
        val valid = mapOf("family" to family, "resource" to "barcode")
        val options = listOf(
            mapOf("fontMode" to "unknown"), mapOf("fontMode" to 1), mapOf("fonts" to "barcode"),
            mapOf("fontMode" to "embed", "fonts" to listOf(valid, valid)),
            mapOf("fontMode" to "embed", "fonts" to listOf(valid + ("embeddingAllowed" to true))),
            mapOf("fontMode" to "preserve", "fonts" to listOf(valid)),
            mapOf("fontMode" to "validate", "fonts" to listOf(valid + ("face" to "invalid"))),
            mapOf("fontMode" to "validate", "fonts" to listOf(valid + ("resource" to "../font"))),
        )
        options.forEach { option ->
            val assessment = provider.assess(GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX, option)))
            assertEquals(AssessmentStatus.UNSUPPORTED, assessment.status)
            assertTrue(assessment.diagnostics.all { it.code == DiagnosticCodes.UNSUPPORTED_OPTION })
        }
        assertEquals(listOf("fontMode", "fontSelection", "fonts"), provider.capabilities().outputOptions.getValue(DocumentFormat.DOCX).map { it.name })
    }

    @Test
    fun `font declaration boundaries retain structured reasons instead of private option values`() {
        val valid = mapOf("family" to family, "resource" to "font")
        val cases = listOf(
            List(65) { valid } to RejectionReason.FONTS_MUST_BE_AN_ARRAY_WITH_AT_MOST_64,
            listOf("PRIVATE_TOKEN") to RejectionReason.INVALID_FONT_RESOURCE_DECLARATION,
            listOf(valid + ("PRIVATE_TOKEN" to true)) to RejectionReason.INVALID_FONT_RESOURCE_DECLARATION,
            listOf(emptyMap<String, Any?>()) to RejectionReason.A_FONT_NEEDS_A_FAMILY,
            listOf(valid + ("family" to 1)) to RejectionReason.A_FONT_NEEDS_A_FAMILY,
            listOf(mapOf("family" to family)) to RejectionReason.A_FONT_NEEDS_A_LOGICAL_RESOURCE_IDENTIFIER,
            listOf(valid + ("family" to " ")) to RejectionReason.INVALID_FONT_FAMILY_FACE_OR_RESOURCE_IDENTIFIER,
            listOf(valid + ("family" to "x".repeat(129))) to RejectionReason.INVALID_FONT_FAMILY_FACE_OR_RESOURCE_IDENTIFIER,
            listOf(valid + ("family" to "PRIVATE_TOKEN\n")) to RejectionReason.INVALID_FONT_FAMILY_FACE_OR_RESOURCE_IDENTIFIER,
            listOf(valid + ("face" to 1)) to RejectionReason.INVALID_FONT_FAMILY_FACE_OR_RESOURCE_IDENTIFIER,
            listOf(valid, valid + ("resource" to "different")) to RejectionReason.FONT_FAMILY_FACE_DECLARATIONS_MUST_BE_UNIQUE,
        )
        for ((raw, expected) in cases) {
            val assessment = DocxProvider().assess(GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.DOCX, mapOf("fontMode" to "validate", "fonts" to raw))))
            assertEquals(AssessmentStatus.UNSUPPORTED, assessment.status)
            assertSame(expected, assessment.diagnostics.single().rejectionReason)
            assertFalse(assessment.diagnostics.single().message.contains("PRIVATE_TOKEN"))
        }
        for (face in listOf("regular", "bold", "italic", "boldItalic")) {
            assertEquals(AssessmentStatus.SUPPORTED, DocxProvider().assess(GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.DOCX, mapOf("fontMode" to "validate", "fonts" to listOf(valid + ("face" to face)))))).status)
        }
    }

    @Test
    fun `existing embedded faces survive preserve but cannot be silently replaced`() {
        val embedded = generate(fixture(true), "embed")
        val preserved = generate(embedded)
        val name = embedded.keys.single { it.endsWith(".odttf") }
        assertArrayEquals(embedded.getValue(name), preserved.getValue(name))
        assertEquals(SyntheticDocx.semantic(embedded.getValue("word/fontTable.xml")), SyntheticDocx.semantic(preserved.getValue("word/fontTable.xml")))
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(embedded).inputStream(), requirements("embed"), data,
                mapOf("barcode" to ResourceSource { font.inputStream() })), output, GenerationContext())
        }
        assertEquals(DiagnosticCodes.UNSUPPORTED_OPTION, error.diagnostics.single().code)
        assertEquals(0, output.size())
    }

    @Test
    fun `concurrent embedded fonts use independent parts and keys`() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val jobs = (1..6).map { executor.submit(java.util.concurrent.Callable { generate(fixture(true), "embed") }) }
            val results = jobs.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(6, results.map { result -> result.keys.single { it.endsWith(".odttf") } }.distinct().size)
            assertEquals(6, results.map { result -> Regex("w:fontKey=\"([^\"]+)\"").find(result.getValue("word/fontTable.xml").decodeToString())!!.groupValues[1] }.distinct().size)
            results.forEach { result -> assertTrue(result.getValue("word/document.xml").decodeToString().contains("NF001")) }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `replacement does not encode or uppercase a barcode reference`() {
        val parts = fixture(true).apply { this["word/document.xml"] = getValue("word/document.xml").decodeToString()
            .replace("*{ref", "{ref").replace("erence}*", "erence}").toByteArray() }
        val output = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), requirements("preserve"), data + ("reference" to "nf001")), output, GenerationContext())
        val xml = SyntheticDocx.unzip(output.toByteArray()).getValue("word/document.xml").decodeToString()
        assertTrue(xml.contains("nf001")); assertFalse(xml.contains("NF001")); assertFalse(xml.contains("*nf001*"))
        assertFalse(Regex("[0-9A-Z .$/+%-]+").matches("nf001"))
    }

    @Test
    fun `generate independent barcode layout and embedding references`() {
        val directory = Path.of("build/font-reference")
        Files.createDirectories(directory)
        val original = fixture()
        val expected = original.mapValues { (name, bytes) -> if (name in setOf("word/document.xml", "word/header1.xml", "word/footer1.xml"))
            bytes.decodeToString().replace("*{ref", "*NF001").replace("erence}*", "*").replace("{customer.name}", "Ada")
                .replace("{customer.", "Ada").replace("name}", "").replace("{reference}", "NF001").toByteArray() else bytes }
        Files.write(directory.resolve("template.docx"), SyntheticDocx.zip(original))
        Files.write(directory.resolve("expected.docx"), SyntheticDocx.zip(expected))
        Files.write(directory.resolve("generated.docx"), SyntheticDocx.zip(generate(original)))
        Files.write(directory.resolve("embedded.docx"), SyntheticDocx.zip(generate(fixture(true), "embed")))
        val plain = fixture(true).mapValues { (_, bytes) -> if (bytes.firstOrNull() == '<'.code.toByte()) bytes.decodeToString()
            .replace("*{ref", "*NF001").replace("erence}*", "*").toByteArray() else bytes }
        Files.write(directory.resolve("embedded-expected.docx"), SyntheticDocx.zip(plain))
        val unframed = fixture(true).apply { this["word/document.xml"] = getValue("word/document.xml").decodeToString()
            .replace("*{ref", "{ref").replace("erence}*", "erence}").toByteArray() }
        Files.write(directory.resolve("unframed.docx"), SyntheticDocx.zip(generate(unframed)))
        val isolated = fixture(true).mapValues { (_, bytes) -> if (bytes.firstOrNull() == '<'.code.toByte())
            bytes.decodeToString().replace(family, "NFTestFont639024").toByteArray() else bytes }
        val declaration = listOf(mapOf("family" to "NFTestFont639024", "resource" to "barcode"))
        Files.write(directory.resolve("isolated-embedded.docx"), SyntheticDocx.zip(generate(isolated, "embed", isolatedFont(), declaration)))
        Files.write(directory.resolve("isolated-control.docx"), SyntheticDocx.zip(generate(isolated)))
    }
}
