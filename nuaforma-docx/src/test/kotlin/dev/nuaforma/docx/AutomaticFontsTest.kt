package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class AutomaticFontsTest {
    @TempDir lateinit var root: Path
    private val alpha = "NF Alpha Fixture"
    private val beta = "NF Beta FixtureX"
    private val unused = "NF Unused Family"
    private val faces = listOf("regular", "bold", "italic", "boldItalic")

    private fun font(family: String, face: String = "regular", permissions: Int = 0): ByteArray {
        val bytes = javaClass.getResourceAsStream("/fonts/LibreBarcode39-Regular.ttf")!!.use { it.readAllBytes() }
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
                val value = when (id) { 1, 16 -> family; else -> family.replace(' ', 'X').padEnd(length / 2, 'X').take(length / 2) }
                val encoded = value.toByteArray(Charsets.UTF_16BE)
                check(encoded.size == length)
                encoded.copyInto(bytes, name + strings + offset)
            }
        }
        val os2 = buffer.getInt(tables.getValue("OS/2") + 8)
        buffer.putShort(os2 + 8, permissions.toShort())
        buffer.putShort(os2 + 62, when (face) { "bold" -> 32; "italic" -> 1; "boldItalic" -> 33; else -> 64 }.toShort())
        fun checksum(start: Int, length: Int): Long = (0 until length step 4).fold(0L) { sum, offset ->
            var word = 0L
            repeat(4) { index -> word = (word shl 8) or if (offset + index < length) (bytes[start + offset + index].toInt() and 255).toLong() else 0 }
            (sum + word) and 0xFFFFFFFFL
        }
        val head = buffer.getInt(tables.getValue("head") + 8)
        buffer.putInt(head + 8, 0)
        tables.values.forEach { record -> buffer.putInt(record + 4, checksum(buffer.getInt(record + 8), buffer.getInt(record + 12)).toInt()) }
        buffer.putInt(head + 8, ((0xB1B0AFBAL - checksum(0, bytes.size)) and 0xFFFFFFFFL).toInt())
        return bytes
    }

    private fun fixture(): MutableMap<String, ByteArray> = SyntheticDocx.parts().mapValues { (name, bytes) ->
        if (name.endsWith(".xml")) bytes.decodeToString().replace("Arial", alpha).toByteArray() else bytes
    }.toMutableMap().apply {
        this["word/header1.xml"] = getValue("word/header1.xml").decodeToString().replace("<w:color", "<w:rFonts w:ascii=\"$beta\" w:hAnsi=\"$beta\"/><w:color").toByteArray()
        this["word/document.xml"] = getValue("word/document.xml").decodeToString().replace("<w:rPr><w:i/>", "<w:rPr><w:b/><w:i/>").toByteArray()
        this["word/footer1.xml"] = getValue("word/footer1.xml").decodeToString().replace("<w:r>", "<w:r><w:rPr><w:i/></w:rPr>").toByteArray()
        // Preserve a genuinely unused style: automatic discovery must not require it.
        this["word/styles.xml"] = getValue("word/styles.xml").decodeToString().replace("</w:styles>",
            """<w:style w:type="paragraph" w:styleId="Unused"><w:rPr><w:rFonts w:ascii="$unused"/></w:rPr></w:style></w:styles>""").toByteArray()
    }

    private fun catalog(image: Boolean = true, restricted: Boolean = false): DeploymentFonts {
        val bundled = Files.createDirectories(root.resolve("image/nested"))
        val mounted = Files.createDirectories(root.resolve("mount"))
        for ((index, family) in listOf(alpha, beta, unused).withIndex()) for (face in faces) {
            Files.write((if (image && index == 0) bundled else mounted).resolve("opaque-$index-$face.ttf"), font(family, face, if (restricted) 2 else 0))
        }
        return DeploymentFonts.load(if (image) listOf(bundled.parent) else emptyList(), mounted)
    }

    private fun requirements(options: Map<String, Any?> = emptyMap()) = GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
        FormatRequest(DocumentFormat.DOCX, mapOf("fontMode" to "embed", "fontSelection" to "used") + options))

    private fun generate(provider: DocxProvider, parts: Map<String, ByteArray> = fixture(), options: Map<String, Any?> = emptyMap(),
        resources: Map<String, ResourceSource> = emptyMap(), context: GenerationContext = GenerationContext()): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        try {
            provider.generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), requirements(options),
                mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF001"), resources), output, context)
        } catch (error: GenerationException) { assertEquals(0, output.size()); throw error }
        return SyntheticDocx.unzip(output.toByteArray())
    }

    private fun decoded(parts: Map<String, ByteArray>): Map<Pair<String, String>, ByteArray> {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val table = factory.newDocumentBuilder().parse(parts.getValue("word/fontTable.xml").inputStream())
        val rels = factory.newDocumentBuilder().parse(parts.getValue("word/_rels/fontTable.xml.rels").inputStream()).documentElement.childNodes
        val targets = (0 until rels.length).mapNotNull { rels.item(it) as? org.w3c.dom.Element }.associate { it.getAttribute("Id") to it.getAttribute("Target") }
        val result = mutableMapOf<Pair<String, String>, ByteArray>()
        val entries = table.getElementsByTagNameNS(SyntheticDocx.W, "font")
        for (index in 0 until entries.length) {
            val entry = entries.item(index) as org.w3c.dom.Element
            for ((face, tag) in faces.zip(listOf("embedRegular", "embedBold", "embedItalic", "embedBoldItalic"))) {
                val refs = entry.getElementsByTagNameNS(SyntheticDocx.W, tag)
                if (refs.length == 0) continue
                assertEquals(1, refs.length)
                val reference = refs.item(0) as org.w3c.dom.Element
                val target = targets.getValue(reference.getAttributeNS(SyntheticDocx.R, "id"))
                val name = Path.of("word").resolve(target).normalize().toString().replace('\\', '/')
                val bytes = parts.getValue(name).clone()
                val hex = reference.getAttributeNS(SyntheticDocx.W, "fontKey").filter { it.isLetterOrDigit() }
                val mask = hex.chunked(2).map { it.toInt(16) }.reversed()
                repeat(32) { bytes[it] = (bytes[it].toInt() xor mask[it % 16]).toByte() }
                result[entry.getAttributeNS(SyntheticDocx.W, "name") to face] = bytes
            }
        }
        return result
    }

    @Test fun `IMAGE and MOUNT embed only used effective families and all mixed faces with independent bytes and structure`() {
        val snapshot = catalog()
        val parts = fixture()
        val result = generate(DocxProvider(snapshot), parts)
        val decoded = decoded(result)
        assertEquals(setOf(alpha to "regular", alpha to "bold", alpha to "italic", alpha to "boldItalic", beta to "regular"), decoded.keys)
        decoded.forEach { (identity, bytes) -> assertArrayEquals(font(identity.first, identity.second), bytes) }
        assertEquals(FontOrigin.BUNDLED, snapshot.catalog.fonts.first { it.family == alpha }.origin)
        assertEquals(FontOrigin.MOUNTED, snapshot.catalog.fonts.first { it.family == beta }.origin)
        assertArrayEquals(parts.getValue("word/media/mark.png"), result.getValue("word/media/mark.png"))
        assertEquals(SyntheticDocx.semantic(parts.getValue("word/numbering.xml")), SyntheticDocx.semantic(result.getValue("word/numbering.xml")))
        val preserve = generate(DocxProvider(), parts, mapOf("fontMode" to "preserve", "fontSelection" to "declared"))
        for (name in listOf("word/document.xml", "word/header1.xml", "word/footer1.xml", "word/styles.xml"))
            assertEquals(SyntheticDocx.semantic(preserve.getValue(name)), SyntheticDocx.semantic(result.getValue(name)))
        val reference = Path.of("build/automatic-font-reference")
        Files.createDirectories(reference)
        Files.write(reference.resolve("embedded.docx"), SyntheticDocx.zip(result))
        Files.write(reference.resolve("preserve.docx"), SyntheticDocx.zip(preserve))
        val declarations = snapshot.catalog.fonts.filter { it.family == alpha || it.face == "regular" }.map {
            mapOf("family" to it.family, "face" to it.face, "resource" to it.resource)
        }
        val explicit = generate(DocxProvider(snapshot), parts, mapOf("fontSelection" to "declared", "fonts" to declarations))
        Files.write(reference.resolve("explicit.docx"), SyntheticDocx.zip(explicit))
    }

    @Test fun `MOUNT alone supports arbitrary families without an IMAGE or request list`() {
        assertEquals(5, decoded(generate(DocxProvider(catalog(false)))).size)
    }

    @Test fun `case variants of the same used family and face embed only once`() {
        val provider = DocxProvider(catalog())
        val parts = fixture()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replaceFirst("<w:rPr><w:b/>",
            """<w:rPr><w:rFonts w:ascii="nf alpha fixture" w:hAnsi="nf alpha fixture"/><w:b/>""").toByteArray()
        assertEquals(5, decoded(generate(provider, parts)).size)
    }

    @Test fun `MOUNT precedes IMAGE even when IMAGE identities conflict and request declarations precede both`() {
        catalog()
        val mounted = font(alpha).apply { this[lastIndex] = 3 }
        Files.write(root.resolve("mount/override.ttf"), mounted)
        val second = Files.createDirectory(root.resolve("image-other"))
        Files.write(second.resolve("same-identity.ttf"), font(alpha).apply { this[lastIndex] = 4 })
        val snapshot = DeploymentFonts.load(listOf(root.resolve("image"), second), root.resolve("mount"))
        val provider = DocxProvider(snapshot)
        assertArrayEquals(mounted, decoded(generate(provider)).getValue(alpha to "regular"))
        val uploaded = font(alpha).apply { this[lastIndex] = 5 }
        val output = generate(provider, options = mapOf("fonts" to listOf(mapOf("family" to alpha, "resource" to "private"))),
            resources = mapOf("private" to ResourceSource { uploaded.inputStream() }))
        assertArrayEquals(uploaded, decoded(output).getValue(alpha to "regular"))
    }

    @Test fun `missing rejected permission restricted and conflicting used faces fail before output`() {
        assertEquals(RejectionReason.USED_FONT_FACE_UNAVAILABLE, assertThrows(GenerationException::class.java) { generate(DocxProvider()) }.diagnostics.single().rejectionReason)
        assertEquals(RejectionReason.FONT_PERMISSIONS_DO_NOT_ALLOW_EDITABLE_OUTLINE_EMBEDDING,
            assertThrows(GenerationException::class.java) { generate(DocxProvider(catalog(restricted = true))) }.diagnostics.single().rejectionReason)
        catalog()
        val mount = root.resolve("mount")
        Files.write(mount.resolve("broken.ttf"), byteArrayOf(0))
        Files.write(mount.resolve("conflict.ttf"), font(beta).apply { this[lastIndex] = 1 })
        assertEquals(RejectionReason.USED_FONT_IDENTITY_CONFLICT,
            assertThrows(GenerationException::class.java) { generate(DocxProvider(DeploymentFonts.load(listOf(root.resolve("image")), mount))) }.diagnostics.single().rejectionReason)
    }

    @Test fun `embedded faces retained across requests and explicit replacement refused`() {
        val provider = DocxProvider(catalog())
        val first = generate(provider)
        val second = generate(DocxProvider(), first)
        for ((name, bytes) in first.filterKeys { it.endsWith(".odttf") }) assertArrayEquals(bytes, second.getValue(name))
        assertEquals(SyntheticDocx.semantic(first.getValue("word/_rels/fontTable.xml.rels")), SyntheticDocx.semantic(second.getValue("word/_rels/fontTable.xml.rels")))
        assertEquals(RejectionReason.REPLACING_AN_EXISTING_EMBEDDED_FONT_FACE_IS_NOT_SUPPORTED,
            assertThrows(GenerationException::class.java) { generate(provider, first,
                mapOf("fonts" to listOf(mapOf("family" to alpha, "resource" to "explicit"))), mapOf("explicit" to ResourceSource { font(alpha).inputStream() })) }.diagnostics.single().rejectionReason)
    }

    @Test fun `explicit resources take precedence and invalid selection is assessed without consuming bytes`() {
        val provider = DocxProvider(catalog())
        val entry = provider.fontCatalog(GenerationContext()).fonts.first { it.family == beta && it.face == "regular" }
        val private = font(beta).apply { this[lastIndex] = 1 }
        val result = generate(provider, options = mapOf("fonts" to listOf(mapOf("family" to beta, "resource" to entry.resource))),
            resources = mapOf(entry.resource!! to ResourceSource { private.inputStream() }))
        assertArrayEquals(private, decoded(result).getValue(beta to "regular"))
        for (mode in listOf("preserve", "validate")) assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(requirements(mapOf("fontMode" to mode))).status)
        assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(requirements(mapOf("fontSelection" to "unknown"))).status)
        assertThrows(GenerationException::class.java) { generate(provider, resources = mapOf("unused" to ResourceSource { error("must not open") })) }
    }

    @Test fun `malformed existing embedded references and obfuscation keys fail without output`() {
        val provider = DocxProvider(catalog())
        val generated = generate(provider)
        val missing = generated.toMutableMap().apply { remove("word/_rels/fontTable.xml.rels") }
        assertThrows(GenerationException::class.java) { generate(provider, missing) }
        val key = generated.toMutableMap().apply {
            this["word/fontTable.xml"] = getValue("word/fontTable.xml").decodeToString()
                .replace(Regex("w:fontKey=\"[^\"]+\""), "w:fontKey=\"invalid\"").toByteArray()
        }
        assertEquals(RejectionReason.EMBEDDED_FONT_KEY_IS_INVALID,
            assertThrows(GenerationException::class.java) { generate(provider, key) }.diagnostics.single().rejectionReason)
    }

    @Test fun `concurrent private resource overrides cannot cross request boundaries and budgets remain enforced`() {
        val provider = DocxProvider(catalog())
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0..7).map { index -> pool.submit<Boolean> {
                val bytes = font(beta).apply { this[lastIndex] = index.toByte() }
                val result = generate(provider, options = mapOf("fonts" to listOf(mapOf("family" to beta, "resource" to "private"))),
                    resources = mapOf("private" to ResourceSource { bytes.inputStream() }))
                bytes.contentEquals(decoded(result).getValue(beta to "regular"))
            } }
            assertTrue(futures.all { it.get() })
        } finally { pool.shutdownNow() }
        assertThrows(GenerationException::class.java) { generate(provider, context = GenerationContext(GenerationLimits(maxExpandedBytes = 100))) }
    }

    @Test fun `Latin theme defaults and inherited style toggles resolve while unused declarations stay unused`() {
        val provider = DocxProvider(catalog())
        val parts = fixture()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString()
            .replace("w:ascii=\"$alpha\" w:hAnsi=\"$alpha\"", "w:asciiTheme=\"minorAscii\" w:hAnsiTheme=\"minorHAnsi\"")
            .toByteArray()
        parts["word/theme/theme1.xml"] = """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Synthetic"><a:themeElements><a:fontScheme name="Synthetic"><a:majorFont><a:latin typeface="$beta"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="$alpha"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme></a:themeElements></a:theme>""".toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>",
            """<Override PartName="/word/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>""").toByteArray()
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
            """<Relationship Id="theme" Type="${SyntheticDocx.R}/theme" Target="theme/theme1.xml"/></Relationships>""").toByteArray()
        assertEquals(5, decoded(generate(provider, parts)).size)
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("minorAscii", "minorEastAsia").toByteArray()
        assertEquals(RejectionReason.AUTOMATIC_FONT_UNRESOLVED_THEME,
            assertThrows(GenerationException::class.java) { generate(provider, parts) }.diagnostics.single().rejectionReason)
    }

    @Test fun `numbering has its own effective family and inherited bold italic faces`() {
        val provider = DocxProvider(catalog())
        val parts = fixture()
        parts["word/numbering.xml"] = parts.getValue("word/numbering.xml").decodeToString().replace("</w:lvl>",
            """<w:rPr><w:rFonts w:ascii="$beta" w:hAnsi="$beta"/><w:b/><w:i/></w:rPr></w:lvl>""").toByteArray()
        assertTrue(beta to "boldItalic" in decoded(generate(provider, parts)).keys)
    }

    @Test fun `character inheritance toggles and direct off overrides do not require unused family faces`() {
        val snapshot = catalog()
        val parts = fixture()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("</w:styles>",
            """<w:style w:type="character" w:styleId="Parent"><w:rPr><w:b/></w:rPr></w:style><w:style w:type="character" w:styleId="Child"><w:basedOn w:val="Parent"/><w:rPr><w:i/></w:rPr></w:style></w:styles>""").toByteArray()
        parts["word/document.xml"] = """<w:document xmlns:w="${SyntheticDocx.W}"><w:body><w:p><w:r><w:rPr><w:rStyle w:val="Child"/><w:i w:val="0"/></w:rPr><w:t>Only bold</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""".toByteArray()
        parts["word/header1.xml"] = """<w:hdr xmlns:w="${SyntheticDocx.W}"/>""".toByteArray()
        parts["word/footer1.xml"] = """<w:ftr xmlns:w="${SyntheticDocx.W}"/>""".toByteArray()
        assertEquals(setOf(alpha to "bold"), decoded(generate(DocxProvider(snapshot), parts)).keys)
    }

    @Test fun `automatic discovery face count and uploaded byte limits fail before publication`() {
        val provider = DocxProvider(catalog())
        val parts = fixture()
        val runs = (0..64).joinToString("") { """<w:r><w:rPr><w:rFonts w:ascii="Unique$it"/></w:rPr><w:t>x</w:t></w:r>""" }
        parts["word/document.xml"] = """<w:document xmlns:w="${SyntheticDocx.W}"><w:body><w:p>$runs</w:p><w:sectPr/></w:body></w:document>""".toByteArray()
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, assertThrows(GenerationException::class.java) { generate(provider, parts) }.diagnostics.single().code)
        val declarations = mapOf("fonts" to listOf(mapOf("family" to beta, "resource" to "oversize")))
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, assertThrows(GenerationException::class.java) {
            generate(provider, options = declarations, resources = mapOf("oversize" to ResourceSource { ByteArray(16 * 1024 * 1024 + 1).inputStream() }))
        }.diagnostics.single().code)
    }

    @Test fun `font aliases sharing one resource still count every embedded face toward the byte budget`() {
        val bytes = font(alpha).copyOf(16 * 1024 * 1024)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val table = (0 until (buffer.getShort(4).toInt() and 65535)).single { String(bytes, 12 + it * 16, 4, Charsets.US_ASCII) == "name" }
        val offset = buffer.getInt(20 + table * 16)
        repeat(buffer.getShort(offset + 2).toInt() and 65535) { index ->
            val record = offset + 6 + index * 12
            val platform = buffer.getShort(record).toInt() and 65535
            val length = buffer.getShort(record + 8).toInt() and 65535
            if (platform == 3 && length in 2..256) buffer.putShort(record + 6, 1)
        }
        val families = dev.nuaforma.docx.internal.OpenTypeMetadata.read(bytes).families.take(5)
        assertEquals(5, families.size)
        Files.write(root.resolve("aliases.ttf"), bytes)
        val provider = DocxProvider(DeploymentFonts.load(root))
        val parts = fixture()
        val runs = families.joinToString("") { family ->
            val escaped = family.replace("&", "&amp;").replace("\"", "&quot;")
            """<w:r><w:rPr><w:rFonts w:ascii="$escaped"/></w:rPr><w:t>x</w:t></w:r>"""
        }
        parts["word/document.xml"] = """<w:document xmlns:w="${SyntheticDocx.W}"><w:body><w:p>$runs</w:p><w:sectPr/></w:body></w:document>""".toByteArray()
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED,
            assertThrows(GenerationException::class.java) { generate(provider, parts) }.diagnostics.single().code)
    }

    @Test fun `cyclic inheritance missing defaults complex scripts and conditional table styles fail explicitly`() {
        val provider = DocxProvider(catalog())
        val changes: List<(MutableMap<String, ByteArray>) -> Unit> = listOf(
            { parts -> parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("<w:name w:val=\"Normal\"/>", "<w:name w:val=\"Normal\"/><w:basedOn w:val=\"Title\"/>").toByteArray() },
            { parts -> parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace(Regex("<w:docDefaults>.*?</w:docDefaults>"), "").toByteArray() },
            { parts -> parts["word/header1.xml"] = parts.getValue("word/header1.xml").decodeToString().replace("NuaForma", "漢字").toByteArray() },
            { parts -> parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("<w:tblPr>", "<w:tblPr><w:tblStyle w:val=\"Conditional\"/>").toByteArray() },
        )
        for (change in changes) {
            val parts = fixture().also(change)
            val reason = assertThrows(GenerationException::class.java) { generate(provider, parts) }.diagnostics.single().rejectionReason
            assertTrue(reason in setOf(RejectionReason.INVALID_FONT_STYLE_INHERITANCE, RejectionReason.UNSUPPORTED_EFFECTIVE_FONT_CONSTRUCT,
                RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_SCRIPT, RejectionReason.AUTOMATIC_FONT_UNSUPPORTED_TABLE_STYLE))
        }
    }
}
