package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors

class DocxDocumentsTest {
    @Test
    fun `round trip preserves the independent reference package`() {
        val original = SyntheticDocx.parts()
        val input = SyntheticDocx.zip(original)
        val result = roundTrip(input)
        val restored = SyntheticDocx.unzip(result)
        assertEquals(original.keys, restored.keys)
        val qa = Path.of("build", "docx-reference")
        Files.createDirectories(qa)
        Files.write(qa.resolve("before.docx"), input)
        Files.write(qa.resolve("after.docx"), result)
        assertAll(original.map { (name, content) -> org.junit.jupiter.api.function.Executable {
            if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(content), SyntheticDocx.semantic(restored.getValue(name)), name)
            else assertArrayEquals(content, restored.getValue(name), name)
        } })
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed", "entity", "traversal", "broken-link", "wrong-root", "duplicate-id", "no-body", "invalid-number", "unknown-word-element", "xml-depth"])
    fun `invalid documents have content-safe diagnostics`(variant: String) {
        val parts = SyntheticDocx.parts()
        when (variant) {
            "missing" -> parts.remove("word/document.xml")
            "malformed" -> parts["word/document.xml"] = "<SECRET broken".toByteArray()
            "entity" -> parts["word/document.xml"] = """<!DOCTYPE document [<!ENTITY x SYSTEM "file:///SECRET">]><w:document xmlns:w="${SyntheticDocx.W}"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>""".toByteArray()
            "traversal" -> parts["../SECRET"] = byteArrayOf(1)
            "broken-link" -> parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("media/mark.png", "media/SECRET.png").toByteArray()
            "wrong-root" -> parts["word/document.xml"] = "<not-a-document/>".toByteArray()
            "duplicate-id" -> parts["_rels/.rels"] = parts.getValue("_rels/.rels").decodeToString().replace("Id=\"r2\"", "Id=\"r1\"").toByteArray()
            "no-body" -> parts["word/document.xml"] = """<w:document xmlns:w="${SyntheticDocx.W}"/>""".toByteArray()
            "invalid-number" -> parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("w:val=\"22\"", "w:val=\"SECRET\"").toByteArray()
            "unknown-word-element" -> parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body><w:SECRET/>").toByteArray()
            "xml-depth" -> parts["word/document.xml"] = ("""<w:document xmlns:w="${SyntheticDocx.W}">""" + "<w:p>".repeat(300) + "</w:p>".repeat(300) + "</w:document>").toByteArray()
        }
        val exception = assertThrows(GenerationException::class.java) { DocxDocuments.open(SyntheticDocx.zip(parts).inputStream()) }
        assertTrue(exception.diagnostics.isNotEmpty())
        assertFalse(exception.diagnostics.joinToString { it.message }.contains("SECRET"))
        assertNull(exception.cause)
    }

    @ParameterizedTest
    @ValueSource(strings = ["macro", "external-image", "ole", "strict", "html", "signed", "wrong-format"])
    fun `unsupported content fails without stripping or fetching`(variant: String) {
        val parts = SyntheticDocx.parts()
        when (variant) {
            "macro" -> parts["word/vbaProject.bin"] = byteArrayOf(1)
            "ole" -> parts["word/embeddings/object.bin"] = byteArrayOf(1)
            "signed" -> parts["_xmlsignatures/sig1.xml"] = "<signature/>".toByteArray()
            "strict" -> parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace(SyntheticDocx.W, "http://purl.oclc.org/ooxml/wordprocessingml/main").toByteArray()
            "wrong-format" -> parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("wordprocessingml.document.main+xml", "spreadsheetml.sheet.main+xml").toByteArray()
            "html" -> parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("image/png", "text/html").toByteArray()
            "external-image" -> parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("Target=\"media/mark.png\"", "Target=\"http://127.0.0.1:1/SECRET\" TargetMode=\"External\"").toByteArray()
        }
        failure(SyntheticDocx.zip(parts), DiagnosticCodes.UNSUPPORTED_INPUT)
    }

    @Test
    fun `truncated and non ZIP inputs are rejected`() {
        val bytes = SyntheticDocx.zip(SyntheticDocx.parts())
        failure(bytes.copyOf(bytes.size - 20), DiagnosticCodes.INVALID_TEMPLATE)
        failure("not a DOCX".toByteArray(), DiagnosticCodes.INVALID_TEMPLATE)
        val corrupt = bytes.copyOf()
        val directory = (0 until corrupt.size - 4).first { offset ->
            corrupt[offset] == 0x50.toByte() && corrupt[offset + 1] == 0x4b.toByte() && corrupt[offset + 2] == 1.toByte() && corrupt[offset + 3] == 2.toByte()
        }
        corrupt[directory + 16] = (corrupt[directory + 16].toInt() xor 1).toByte()
        failure(corrupt, DiagnosticCodes.INVALID_TEMPLATE)
    }

    @Test
    fun `resource budgets reject before publishing output`() {
        val bytes = SyntheticDocx.zip(SyntheticDocx.parts())
        listOf(GenerationLimits(maxTemplateBytes = 10), GenerationLimits(maxExpandedBytes = 100), GenerationLimits(maxArchiveEntries = 2)).forEach {
            failure(bytes, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, GenerationContext(it))
        }
        val output = ByteArrayOutputStream()
        DocxDocuments.open(bytes.inputStream(), GenerationContext(GenerationLimits(maxOutputBytes = 50))).use { document ->
            val failure = assertThrows(GenerationException::class.java) { document.writeTo(output) }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, failure.diagnostics.single().code)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `cancellation deadline closure and stream ownership are explicit`() {
        val bytes = SyntheticDocx.zip(SyntheticDocx.parts())
        failure(bytes, DiagnosticCodes.CANCELLED, GenerationContext(cancellation = CancellationToken { true }))
        failure(bytes, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, GenerationContext(deadline = Instant.EPOCH))
        var cancel = false
        var inputClosed = false
        var outputClosed = false
        val input = object : ByteArrayInputStream(bytes) { override fun close() { inputClosed = true; super.close() } }
        val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true; super.close() } }
        val document = DocxDocuments.open(input, GenerationContext(cancellation = CancellationToken { cancel }))
        document.writeTo(output)
        cancel = true
        assertEquals(DiagnosticCodes.CANCELLED, assertThrows(GenerationException::class.java) { document.writeTo(output) }.diagnostics.single().code)
        cancel = false
        document.close()
        document.close()
        assertEquals(DiagnosticCodes.INVALID_REQUEST, assertThrows(GenerationException::class.java) { document.writeTo(output) }.diagnostics.single().code)
        assertFalse(inputClosed)
        assertFalse(outputClosed)
    }

    @Test
    fun `caller I O errors propagate without closing the destination`() {
        DocxDocuments.open(SyntheticDocx.zip(SyntheticDocx.parts()).inputStream()).use { document ->
            assertThrows(IOException::class.java) { document.writeTo(object : OutputStream() {
                override fun write(value: Int) { throw IOException("destination unavailable") }
            }) }
        }
        assertThrows(IOException::class.java) { DocxDocuments.open(object : java.io.InputStream() {
            override fun read(): Int { throw IOException("source unavailable") }
        }) }
    }

    @Test
    fun `concurrent documents do not share content`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..8).map { number -> pool.submit<String> {
                val output = roundTrip(SyntheticDocx.zip(SyntheticDocx.parts("Isolated $number")))
                SyntheticDocx.unzip(output).getValue("word/document.xml").decodeToString()
            } }
            tasks.forEachIndexed { index, future ->
                val result = future.get()
                assertTrue(result.contains("Isolated ${index + 1}"))
                (1..8).filter { it != index + 1 }.forEach { assertFalse(result.contains("Isolated $it")) }
            }
        } finally { pool.shutdownNow() }
    }

    private fun roundTrip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DocxDocuments.open(bytes.inputStream()).use { it.writeTo(output) }
    }.toByteArray()

    private fun failure(bytes: ByteArray, code: DiagnosticCode, context: GenerationContext = GenerationContext()) {
        assertEquals(code, assertThrows(GenerationException::class.java) { DocxDocuments.open(bytes.inputStream(), context) }.diagnostics.single().code)
    }
}
