package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import dev.nuaforma.template.TemplatePipes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class DocxValuePipesTest {
    private val wrap = object : TemplatePipe {
        override val definition = TemplatePipeDefinition("wrap", "Wrap a value in a JSON object")
        override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? = mapOf("value" to value)
    }
    private val unwrap = object : TemplatePipe {
        override val definition = TemplatePipeDefinition("unwrap", "Read a wrapped JSON value")
        override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext): Any? {
            context.checkActive()
            return (value as Map<*, *>)["value"]
        }
    }
    private val pipes = TemplatePipes.of(listOf(wrap, unwrap))
    private val data = mapOf("customer" to mapOf("name" to "Ada"), "reference" to "NF-001", "rows" to listOf("A", "B"))
    private fun generate(parts: Map<String, ByteArray>, values: TemplatePipes, watermark: String): ByteArray {
        val output = ByteArrayOutputStream()
        DocxProvider(DeploymentFonts.configured(), emptyList(), values).generate(GenerationRequest(SyntheticDocx.zip(parts).inputStream(),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX),
                GenerationOptions(watermark = TextWatermark(watermark))), data), output, GenerationContext())
        return output.toByteArray()
    }

    @Test fun `typed value chains preserve formatted document parts and resolve the same watermark`() {
        val expectedParts = SyntheticDocx.parts().apply {
            this["word/document.xml"] = getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body>" +
                "<w:p><w:r><w:t>{#each rows as row}{#if loop.first}{row}{#else}{row}{/if}{/each}</w:t></w:r></w:p>").toByteArray()
        }
        val source = expectedParts.mapValues { (name, bytes) ->
            if (name.endsWith(".xml")) bytes.decodeToString().replace("name}", "name | wrap() | unwrap()}")
                .replace("{reference}", "{reference | wrap() | unwrap()}").replace("{row}", "{row | wrap() | unwrap()}").toByteArray()
            else bytes
        }
        val actual = generate(source, pipes, "CONCEPT {reference | wrap() | unwrap()}")
        val expected = generate(expectedParts, TemplatePipes.of(emptyList()), "CONCEPT {reference}")
        val a = SyntheticDocx.unzip(actual); val e = SyntheticDocx.unzip(expected)
        assertEquals(e.keys, a.keys)
        e.forEach { (name, bytes) ->
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                val watermarkIdentity = Regex("NFWatermark[0-9a-f]{32}")
                assertEquals(watermarkIdentity.replace(SyntheticDocx.semantic(bytes), "NFWatermarkUUID"),
                    watermarkIdentity.replace(SyntheticDocx.semantic(a.getValue(name)), "NFWatermarkUUID"), name)
            }
            else assertArrayEquals(bytes, a.getValue(name), name)
        }
        val directory = Path.of("build/value-pipes-reference")
        Files.createDirectories(directory)
        Files.write(directory.resolve("generated.docx"), actual)
        Files.write(directory.resolve("expected.docx"), expected)
    }

    @Test fun `image and custom content names cannot be claimed by a value pipe`() {
        val image = object : TemplatePipe {
            override val definition = TemplatePipeDefinition("image", "Synthetic collision")
            override fun apply(value: Any?, arguments: List<Any?>, context: TemplatePipeContext) = value
        }
        assertThrows(IllegalArgumentException::class.java) { DocxProvider(DeploymentFonts.configured(), emptyList(), TemplatePipes.of(listOf(image))) }
    }
}
