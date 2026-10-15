package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class DocxContentPipesTest {
    private val data = mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF-001", "rows" to listOf("A", "B"))
    private val pipe = object : ContentPipe {
        override val definition = ContentPipeDefinition("content", "Synthetic literal text insertion", support = listOf(
            ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.DOCX), ContentPipeSupport(DocumentFormat.DOCX, DocumentFormat.PDF)))
        override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
            context.checkActive()
            return if (value == null) ContentResult.Empty else ContentResult.Text(value as String)
        }
    }
    private fun parts(content: Boolean): MutableMap<String, ByteArray> = SyntheticDocx.parts().apply {
        val source = getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body>" +
            "<w:p><w:r><w:t>{#each rows as row}{#if loop.first}{row}{#else}{row}{/if}{/each}</w:t></w:r></w:p>")
        this["word/document.xml"] = source.toByteArray()
        if (content) keys.filter { it.endsWith(".xml") }.forEach { name ->
            this[name] = getValue(name).decodeToString().replace("name}", "name | content()}")
                .replace("{reference}", "{reference | content()}").replace("{row}", "{row | content()}").toByteArray()
        }
    }
    private fun generate(parts: Map<String, ByteArray>, provider: DocxProvider, values: Map<String, *> = data,
                         options: GenerationOptions = GenerationOptions()): ByteArray {
        val out = ByteArrayOutputStream()
        provider.generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX), options), values), out, GenerationContext())
        return out.toByteArray()
    }

    @Test
    fun `unknown structured results are rejected without stringification or partial output`() {
        var called = false
        var stringified = false
        val unknown = object : ContentPipe {
            override val definition = pipe.definition
            override fun apply(value: Any?, arguments: List<Any?>, context: ContentPipeContext): ContentResult {
                called = true
                return object : ContentResult {
                    override fun toString(): String {
                        stringified = true
                        throw AssertionError("private-payload")
                    }
                }
            }
        }
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            DocxProvider(DeploymentFonts.configured(), listOf(unknown)).generate(
                GenerationRequest(SyntheticDocx.zip(parts(true)).inputStream(), GenerationRequirements(
                    FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)), data), output, GenerationContext())
        }
        assertTrue(called)
        assertFalse(stringified)
        assertEquals(0, output.size())
        assertEquals(DiagnosticCodes.INVALID_REQUEST, error.diagnostics.single().code)
        assertFalse(error.message.orEmpty().contains("private-payload"))
        assertNull(error.cause)
    }

    @Test
    fun `field instructions and unsupported note comment parts reject content without changing ordinary note policy`() {
        val provider = DocxProvider(DeploymentFonts.configured(), listOf(pipe))
        for ((part, element, child) in listOf(Triple("footnotes", "footnotes", "footnote"), Triple("endnotes", "endnotes", "endnote"), Triple("comments", "comments", "comment"))) {
            val source = parts(false).apply {
                this["[Content_Types].xml"] = getValue("[Content_Types].xml").decodeToString().replace("</Types>",
                    "<Override PartName=\"/word/$part.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.$part+xml\"/></Types>").toByteArray()
                this["word/_rels/document.xml.rels"] = getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
                    "<Relationship Id=\"r99\" Type=\"${SyntheticDocx.R}/$part\" Target=\"$part.xml\"/></Relationships>").toByteArray()
                this["word/$part.xml"] = "<w:$element xmlns:w=\"${SyntheticDocx.W}\"><w:$child w:id=\"1\"${if (part == "comments") " w:author=\"NF\"" else ""}><w:p><w:r><w:tab/><w:t>{reference | content()}</w:t></w:r></w:p></w:$child></w:$element>".toByteArray()
            }
            assertThrows(GenerationException::class.java) { generate(source, provider) }
            source["word/$part.xml"] = source.getValue("word/$part.xml").decodeToString().replace(" | content()", "").toByteArray()
            assertTrue(generate(source, provider).isNotEmpty())
        }
        for (field in listOf("<w:fldSimple w:instr=\"{reference | content()}\"><w:r><w:t>Displayed</w:t></w:r></w:fldSimple>",
            "<w:r><w:instrText>{reference | con</w:instrText></w:r><w:r><w:instrText>tent()}</w:instrText></w:r>",
            "<w:fldSimple w:instr=\"MERGEFIELD reference\"><w:r><w:t>{reference | content()}</w:t></w:r></w:fldSimple>",
            "<w:sdt><w:sdtPr/><w:sdtContent><w:r><w:t>{reference | content()}</w:t></w:r></w:sdtContent></w:sdt>")) {
            val source = parts(false).apply {
                this["word/document.xml"] = getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body><w:p>$field</w:p>").toByteArray()
            }
            assertThrows(GenerationException::class.java) { generate(source, provider) }
        }
    }

    @Test
    fun `content insertion equals ordinary values through split runs tables headers footers text boxes and nested scopes`() {
        val actual = generate(parts(true), DocxProvider(DeploymentFonts.configured(), listOf(pipe)))
        val expected = generate(parts(false), DocxProvider())
        val a = SyntheticDocx.unzip(actual); val e = SyntheticDocx.unzip(expected)
        assertEquals(e.keys, a.keys)
        e.forEach { (name, bytes) ->
            if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(bytes), SyntheticDocx.semantic(a.getValue(name)), name)
            else assertArrayEquals(bytes, a.getValue(name), name)
        }
        val directory = Path.of("build/content-pipes-reference")
        Files.createDirectories(directory)
        Files.write(directory.resolve("generated.docx"), actual)
        Files.write(directory.resolve("expected.docx"), expected)
    }

    @Test
    fun `unknown chains watermark and mixed object positions fail before output publication`() {
        val provider = DocxProvider(DeploymentFonts.configured(), listOf(pipe))
        for (kind in listOf("chain", "unknown", "mixed", "watermark")) {
            val parts = parts(true)
            if (kind == "chain") parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("| content()", "| content() | content()").toByteArray()
            if (kind == "unknown") parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("| content()", "| absent()").toByteArray()
            if (kind == "mixed") parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
                .replace("<w:t>DOCX round trip reference</w:t>", "<w:tab/><w:t>{reference | content()}</w:t>").toByteArray()
            val output = ByteArrayOutputStream()
            assertThrows(GenerationException::class.java) {
                provider.generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(), GenerationRequirements(
                    FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
                    if (kind == "watermark") GenerationOptions(watermark = TextWatermark("{reference | content()}")) else GenerationOptions()), data), output, GenerationContext())
            }
            assertEquals(0, output.size(), kind)
        }
    }

    @Test
    fun `provider scopes metadata and isolates literal values across concurrent requests`() {
        val provider = DocxProvider(DeploymentFonts.configured(), listOf(pipe))
        assertTrue(provider.contentPipes().single().support.all { it.output == DocumentFormat.DOCX })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..4).map { n -> executor.submit<String> {
                SyntheticDocx.unzip(generate(parts(true), provider, data + ("reference" to "{request$n}")))
                    .getValue("word/document.xml").decodeToString()
            } }
            results.forEachIndexed { index, result -> assertTrue(result.get().contains("{request${index + 1}}")) }
        } finally { executor.shutdownNow() }
    }
}
