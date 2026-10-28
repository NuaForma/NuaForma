package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader

class DocxProviderTest {
    private val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
    private val data = mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF-001", "active" to true,
        "groups" to listOf(mapOf("name" to "A", "items" to listOf("x", "y")), mapOf("name" to "B", "items" to listOf("z"))))

    private fun generate(parts: Map<String, ByteArray> = SyntheticDocx.parts(), values: Map<String, *> = data,
        context: GenerationContext = GenerationContext()): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), requirements, values), output, context)
        return SyntheticDocx.unzip(output.toByteArray())
    }

    private fun paragraph(text: String) = "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"
    private fun insert(block: String): MutableMap<String, ByteArray> = SyntheticDocx.parts().apply {
        this["word/document.xml"] = getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body>$block").toByteArray()
    }
    private fun row(text: String) = "<w:tr><w:tc>${paragraph(text)}</w:tc></w:tr>"

    @Test
    fun `mixed quote date markers across styled runs preserve equivalent literal document structure`() {
        val expression = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>{day | date(“dd-</w:t></w:r>" +
            "<w:r><w:rPr><w:i/></w:rPr><w:t>MM-yyyy')}</w:t></w:r></w:p>"
        val literal = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">28-10-2026</w:t></w:r></w:p>"
        val actual = generate(insert(expression), data + ("day" to "2026-10-28"))
        val expected = generate(insert(literal))
        assertEquals(expected.keys, actual.keys)
        for (name in expected.keys) {
            if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(expected.getValue(name)), SyntheticDocx.semantic(actual.getValue(name)), name)
            else assertArrayEquals(expected.getValue(name), actual.getValue(name), name)
        }
    }

    @Test
    fun `syntax details retain source paragraph and part through DOCX diagnostics`() {
        val context = GenerationContext(logging = GenerationLogging(LoggingMode.DEVELOPMENT))
        val cases = listOf(
            "word/document.xml" to insert(paragraph("{trim(customer.name,)}")),
            "word/document.xml" to insert("<w:tbl>${row("{trim(customer.name,)}")}</w:tbl>"),
            "word/document.xml" to insert(paragraph("{#if trim(customer.name,)}") + paragraph("yes") + paragraph("{/if}")),
            "word/header1.xml" to SyntheticDocx.parts().apply {
                this["word/header1.xml"] = getValue("word/header1.xml").decodeToString().replace("{customer.name}", "{trim(customer.name,)}").toByteArray()
            },
            "word/footer1.xml" to SyntheticDocx.parts().apply {
                this["word/footer1.xml"] = getValue("word/footer1.xml").decodeToString().replace("{reference}", "{trim(reference,)}").toByteArray()
            },
        )
        for ((part, source) in cases) {
            val error = assertThrows(GenerationException::class.java) { generate(source, context = context) }.diagnostics.single()
            assertEquals(part, error.part)
            assertNotNull(error.templateContext, part)
            assertTrue(error.templateContext!!.expression.contains("trim("), part)
            assertTrue(error.documentLocation?.startsWith("paragraph:") == true, part)
        }
    }

    @Test
    fun `string functions in split runs structural rows and text boxes preserve literal equivalent layout`() {
        val split = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>{trim(customer.</w:t></w:r>" +
            "<w:r><w:rPr><w:i/></w:rPr><w:t>name)}</w:t></w:r></w:p>"
        val conditional = paragraph("{#if startsWith(reference, &quot;NF-&quot;)}") + paragraph("Selected") + paragraph("{/if}")
        val table = "<w:tbl><w:tblPr/><w:tblGrid/>" + row("{#if endsWith(reference, &quot;001&quot;)}") +
            row("{#if contains(reference, &quot;nf&quot;, true)}Reference{/if}") + row("{/if}") + "</w:tbl>"
        val source = insert(split + conditional + table).apply {
            this["word/document.xml"] = getValue("word/document.xml").decodeToString()
                .replace("{customer.", "{trim(customer.").replace("name}", "name)}")
                .replace("{#if active}", "{#if equalsIgnoreCase(status, &quot;approved&quot;)}").toByteArray()
        }
        val values = data + ("status" to "APPROVED")
        val actual = generate(source, values)
        val literal = insert("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>{&quot;Ada&quot;}</w:t></w:r></w:p>" + paragraph("Selected") +
            "<w:tbl><w:tblPr/><w:tblGrid/>" + row("{&quot;Reference&quot;}") + "</w:tbl>")
        val expected = generate(literal, values)
        assertEquals(expected.keys, actual.keys)
        for (name in expected.keys) {
            if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(expected.getValue(name)), SyntheticDocx.semantic(actual.getValue(name)), name)
            else assertArrayEquals(expected.getValue(name), actual.getValue(name), name)
        }
        val directory = Path.of("build/string-functions-reference")
        Files.createDirectories(directory)
        Files.write(directory.resolve("generated.docx"), SyntheticDocx.zip(actual))
        Files.write(directory.resolve("expected.docx"), SyntheticDocx.zip(expected))
    }

    @Test
    fun `split run values reach body headers footers and legacy modern rotated text boxes`() {
        val original = SyntheticDocx.parts()
        val result = generate(original)
        for (name in listOf("word/document.xml", "word/header1.xml", "word/footer1.xml")) {
            val xml = result.getValue(name).decodeToString()
            assertFalse(xml.contains("{customer."), name)
            assertFalse(xml.contains("{reference}"), name)
            assertTrue(xml.contains("Ada") || xml.contains("NF-001"), name)
        }
        val document = result.getValue("word/document.xml").decodeToString()
        val tree = javax.xml.parsers.DocumentBuilderFactory.newDefaultInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(document.byteInputStream())
        val texts = tree.getElementsByTagNameNS(SyntheticDocx.W, "t")
        val visible = (0 until texts.length).joinToString("") { texts.item(it).textContent }
        assertTrue(visible.contains("Text box Ada"))
        assertTrue(visible.contains("Modern text box Ada"))
        assertTrue(document.contains("rotation:90"))
        for (name in original.keys - setOf("word/document.xml", "word/header1.xml", "word/footer1.xml")) {
            if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(original.getValue(name)), SyntheticDocx.semantic(result.getValue(name)), name)
            else assertArrayEquals(original.getValue(name), result.getValue(name), name)
        }
    }

    @Test
    fun `inline conditions and repetitions preserve mixed run ordering and inserted data stays literal`() {
        val block = "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>{#each groups as group}B{group.name}</w:t></w:r>" +
            "<w:r><w:rPr><w:i/></w:rPr><w:t>I{#if loop.last}last{#else}first{/if}{/each}</w:t></w:r></w:p>"
        val xml = generate(insert(block)).getValue("word/document.xml").decodeToString()
        assertTrue(xml.indexOf(">B<") < xml.indexOf(">A<"))
        assertTrue(xml.indexOf(">first<") < xml.indexOf(">last<"))
        assertFalse(xml.contains("{#each"))
        assertTrue(xml.contains("<w:b")); assertTrue(xml.contains("<w:i"))
        val literal = generate(values = data + ("reference" to "{secret}")).getValue("word/document.xml").decodeToString()
        assertTrue(literal.contains("{secret}"))
    }

    @Test
    fun `nested paragraph and row blocks remove markers and restore scopes`() {
        val block = paragraph("{#if active}") + paragraph("Selected") + paragraph("{#else}") + paragraph("Omitted") + paragraph("{/if}") +
            paragraph("{#each groups as group}") + paragraph("Group {group.name}") +
            "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid>" + row("Item") +
            row("{#each group.items as item}") + row("{group.name}:{item}:{loop.number}") + row("{/each}") + "</w:tbl>" + paragraph("{/each}")
        val xml = generate(insert(block)).getValue("word/document.xml").decodeToString()
        assertTrue(xml.contains("Selected")); assertFalse(xml.contains("Omitted"))
        assertTrue(xml.contains(">x<")); assertTrue(xml.contains(">y<")); assertTrue(xml.contains(">z<"))
        assertFalse(xml.contains("{#")); assertFalse(xml.contains("{/"))
    }

    @Test
    fun `repeated bookmarks receive unique names and paired identifiers`() {
        val block = paragraph("{#each groups as group}") + "<w:p><w:bookmarkStart w:id=\"40\" w:name=\"group\"/>" +
            "<w:r><w:t>{group.name}</w:t></w:r><w:bookmarkEnd w:id=\"40\"/></w:p>" + paragraph("{/each}")
        val xml = generate(insert(block)).getValue("word/document.xml").decodeToString()
        val starts = Regex("<w:bookmarkStart[^>]*w:id=\"(\\d+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        val ends = Regex("<w:bookmarkEnd[^>]*w:id=\"(\\d+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(3, starts.size); assertEquals(starts.size, starts.distinct().size); assertEquals(starts.toSet(), ends.toSet())
        assertFalse(xml.contains("w:name=\"group\""))
        val incoming = "<w:p><w:hyperlink w:anchor=\"group\"><w:r><w:t>Outside link</w:t></w:r></w:hyperlink></w:p>"
        assertEquals(DiagnosticCodes.INVALID_TEMPLATE, assertThrows(GenerationException::class.java) { generate(insert(incoming + block)) }.diagnostics.single().code)
    }

    @Test
    fun `invalid unselected syntax fails before publishing any bytes`() {
        val block = paragraph("{#if false}") + paragraph("{name.method()}") + paragraph("{/if}")
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            DocxProvider().generate(GenerationRequest(SyntheticDocx.zip(insert(block)).inputStream(), requirements, data), output, GenerationContext())
        }
        assertEquals(DiagnosticCodes.INVALID_TEMPLATE, error.diagnostics.single().code)
        assertEquals(0, output.size())
    }

    @Test
    fun `empty repeats keep cells valid but cannot silently remove an entire table`() {
        val block = "<w:tbl><w:tblPr/><w:tblGrid/>" + row("{#each empty as item}") + row("{item}") + row("{/each}") + "</w:tbl>"
        val error = assertThrows(GenerationException::class.java) { generate(insert(block), data + ("empty" to emptyList<String>())) }
        assertEquals(DiagnosticCodes.INVALID_TEMPLATE, error.diagnostics.single().code)
        val cell = "<w:tbl><w:tblPr/><w:tblGrid/><w:tr><w:tc>" + paragraph("{#if false}") + paragraph("Gone") + paragraph("{/if}") + "</w:tc></w:tr></w:tbl>"
        val xml = generate(insert(cell)).getValue("word/document.xml").decodeToString()
        assertFalse(xml.contains("Gone")); assertTrue(xml.contains("<w:p/>") || xml.contains("<w:p />"))
    }

    @Test
    fun `registered provider exposes only ready DOCX pairs and descriptor metadata`() {
        val providers = ServiceLoader.load(DocumentProvider::class.java).toList()
        val provider = providers.single { it.javaClass.name == "dev.nuaforma.docx.DocxProvider" }
        assertTrue(provider.capabilities().supports(DocumentFormat.DOCX, DocumentFormat.DOCX))
        assertTrue(provider.availability().supports(DocumentFormat.DOCX, DocumentFormat.DOCX))
        assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(GenerationRequirements(requirements.input, FormatRequest(DocumentFormat.PDF))).status)
        val metadata = javaClass.classLoader.getResourceAsStream(ProviderMetadata.resourcePath(provider.javaClass.name))!!.use { ProviderMetadata.read(it) }
        assertEquals("nuaforma-docx", metadata.id); assertTrue(ContractVersion.CURRENT.accepts(metadata.requiredContract))
    }

    @Test
    fun `generate synthetic layout references for local rendering`() {
        val directory = Path.of("build/docx-generation-reference")
        Files.createDirectories(directory)
        val parts = SyntheticDocx.parts()
        Files.write(directory.resolve("template.docx"), SyntheticDocx.zip(parts))
        Files.write(directory.resolve("generated.docx"), SyntheticDocx.zip(generate(parts)))
        val expected = parts.mapValues { (name, value) -> if (name in setOf("word/document.xml", "word/header1.xml", "word/footer1.xml"))
            value.decodeToString().replace("{customer.name}", "Ada").replace("{customer.", "Ada").replace("name}", "").replace("{reference}", "NF-001").toByteArray()
            else value }
        Files.write(directory.resolve("expected.docx"), SyntheticDocx.zip(expected))
    }

    @Test
    fun `unsafe dynamic ranges and requested options fail explicitly`() {
        val large = paragraph("{#each groups as " + "x".repeat(4096) + "}") + paragraph("unused") + paragraph("{/each}")
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, assertThrows(GenerationException::class.java) { generate(insert(large)) }.diagnostics.single().code)
        val crossing = paragraph("{#if false}") + "<w:p><w:bookmarkStart w:id=\"99\" w:name=\"bad\"/><w:r><w:t>hidden</w:t></w:r></w:p>" +
            paragraph("{/if}") + "<w:p><w:bookmarkEnd w:id=\"99\"/></w:p>"
        assertEquals(DiagnosticCodes.INVALID_TEMPLATE, assertThrows(GenerationException::class.java) { generate(insert(crossing)) }.diagnostics.single().code)
        val provider = DocxProvider()
        assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX, mapOf("unknown" to true)), requirements.output)).status)
        assertEquals(AssessmentStatus.SUPPORTED, provider.assess(GenerationRequirements(requirements.input, requirements.output,
            GenerationOptions(watermark = TextWatermark("CONCEPT")))).status)
    }

    @Test
    fun `structural repetition budget is shared across separate blocks`() {
        val block = paragraph("{#each groups as group}") + paragraph("{group.name}") + paragraph("{/each}") +
            paragraph("{#each groups as group}") + paragraph("{group.name}") + paragraph("{/each}")
        val error = assertThrows(GenerationException::class.java) { generate(insert(block), context = GenerationContext(GenerationLimits(maxRepetitions = 3))) }
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
    }

    @Test
    fun `modern paragraph ids are renewed and mixed control runs retain tabs`() {
        val block = paragraph("{#each groups as group}") +
            "<w:p xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\" w14:paraId=\"00000001\" w14:textId=\"00000002\"><w:r><w:t>{group.name}</w:t><w:tab/><w:t>{reference}</w:t></w:r></w:p>" + paragraph("{/each}")
        val xml = generate(insert(block)).getValue("word/document.xml").decodeToString()
        val ids = Regex("w14:paraId=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(2, ids.size); assertEquals(ids.size, ids.distinct().size)
        assertFalse("00000001" in ids)
        assertEquals(2, Regex("<w:tab\\s*/>").findAll(xml).count())
        assertFalse(xml.contains("{group."))
    }

    @Test
    fun `repeated drawings have unique ids and retain their existing image relationship`() {
        val parts = SyntheticDocx.parts()
        val original = parts.getValue("word/document.xml").decodeToString()
        val image = Regex("<w:p><w:r><w:drawing><wp:inline.*?</w:p>", RegexOption.DOT_MATCHES_ALL).find(original)!!.value
        parts["word/document.xml"] = original.replace(image, paragraph("{#each groups as group}") + image + paragraph("{/each}")).toByteArray()
        val result = generate(parts)
        val xml = result.getValue("word/document.xml").decodeToString()
        val ids = Regex("<wp:docPr[^>]*id=\"(\\d+)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(3, ids.size); assertEquals(ids.size, ids.distinct().size)
        assertEquals(2, Regex("r:embed=\"r5\"").findAll(xml).count())
        assertEquals(SyntheticDocx.semantic(parts.getValue("word/_rels/document.xml.rels")), SyntheticDocx.semantic(result.getValue("word/_rels/document.xml.rels")))
    }

    @Test
    fun `generate independently authored complex structural layout references`() {
        val source = paragraph("{#if active}") + paragraph("Selected condition {#if true}with inline detail{/if}") + paragraph("{#else}") +
            paragraph("Omitted content") + paragraph("{/if}") + paragraph("{#each groups as group}") +
            paragraph("Group {group.name}") + "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid>" +
            row("{#each group.items as item}") + row("{group.name}:{item}:{loop.number}") + row("{/each}") + "</w:tbl>" + paragraph("{/each}")
        val expectedBlock = paragraph("Selected condition with inline detail") + paragraph("Group A") +
            "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid>" + row("A:x:1") + row("A:y:2") + "</w:tbl>" +
            paragraph("Group B") + "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid>" + row("B:z:1") + "</w:tbl>"
        val directory = Path.of("build/docx-generation-reference")
        Files.createDirectories(directory)
        Files.write(directory.resolve("complex-template.docx"), SyntheticDocx.zip(insert(source)))
        Files.write(directory.resolve("complex-generated.docx"), SyntheticDocx.zip(generate(insert(source))))
        val expected = insert(expectedBlock).mapValues { (name, bytes) -> if (name in setOf("word/document.xml", "word/header1.xml", "word/footer1.xml"))
            bytes.decodeToString().replace("{customer.name}", "Ada").replace("{customer.", "Ada").replace("name}", "").replace("{reference}", "NF-001").toByteArray() else bytes }
        Files.write(directory.resolve("complex-expected.docx"), SyntheticDocx.zip(expected))
    }
}
