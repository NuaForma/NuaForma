package dev.nuaforma.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import dev.nuaforma.core.ProviderRegistry
import dev.nuaforma.server.adapters.json.KotlinxJsonCodec
import dev.nuaforma.server.adapters.ktor.KtorServer
import dev.nuaforma.server.internal.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenApiTest {
    @TempDir lateinit var temporary: Path
    private val mapper = ObjectMapper()

    @Test
    fun `YAML emitter preserves JSON scalar types quotes Unicode and empty containers`() {
        val model = mapOf("strings" to listOf("null", "true", "001", "on", "# anchor: value", "quote \" and \\ slash", "line\nnext\ttab", "Ada — 😀"),
            "values" to listOf(null, true, false, 12, 128L * 1024 * 1024, 0.25, -45),
            "emptyObject" to emptyMap<String, Any>(), "emptyArray" to emptyList<Any>())
        val docs = ApiDocumentation(KotlinxJsonCodec(), model)
        assertEquals(mapper.readTree(docs.jsonText), ObjectMapper(YAMLFactory()).readTree(docs.yamlText))
    }

    private fun assertOpenApi(source: String) {
        val tree = if (source.trimStart().startsWith("{")) mapper.readTree(source) else ObjectMapper(YAMLFactory()).readTree(source)
        fun localReferences(value: JsonNode) {
            if (value.has("\$ref")) assertTrue(value.path("\$ref").asText().startsWith("#/components/schemas/"))
            value.forEach(::localReferences)
        }
        localReferences(tree)
        val options = ParseOptions().apply {
            isResolve = false
            isResolveFully = false
            isValidateExternalRefs = false
            isValidateInternalRefs = true
        }
        val parsed = OpenAPIV3Parser().readContents(source, null, options)
        assertNotNull(parsed.openAPI, parsed.messages.joinToString())
        assertTrue(parsed.messages.isEmpty(), parsed.messages.joinToString())
        assertEquals("3.1.1", parsed.openAPI.openapi)
    }

    @Test
    fun `both real downloads are identical parseable contracts with correct media types and filenames`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val json = client.get(ApiRoutes.OPENAPI_JSON)
        val yaml = client.get(ApiRoutes.OPENAPI_YAML)
        assertEquals(HttpStatusCode.OK, json.status); assertEquals(HttpStatusCode.OK, yaml.status)
        assertTrue(json.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        assertTrue(yaml.headers[HttpHeaders.ContentType]!!.startsWith("application/yaml"))
        assertEquals("attachment; filename=\"nuaforma-openapi.json\"", json.headers[HttpHeaders.ContentDisposition])
        assertEquals("attachment; filename=\"nuaforma-openapi.yaml\"", yaml.headers[HttpHeaders.ContentDisposition])
        assertEquals("nosniff", yaml.headers["X-Content-Type-Options"])
        assertOpenApi(json.bodyAsText()); assertOpenApi(yaml.bodyAsText())
        val jsonTree = mapper.readTree(json.bodyAsText())
        assertEquals(jsonTree, ObjectMapper(YAMLFactory()).readTree(yaml.bodyAsText()))
        val actualOperations = jsonTree.path("paths").properties().flatMap { (path, value) -> value.fieldNames().asSequence().map { path to it }.toList() }.toMap()
        assertEquals(ApiRoutes.operations, actualOperations)
        assertEquals("/", jsonTree.path("servers").first().path("url").asText())
        assertEquals(mapper.readTree(ApiDocumentation().jsonText), jsonTree)
        val exported = temporary.resolve("export")
        OpenApiExport.main(arrayOf(exported.toString()))
        assertEquals(jsonTree, mapper.readTree(Files.readString(exported.resolve("nuaforma-openapi.json"))))
        assertEquals(jsonTree, ObjectMapper(YAMLFactory()).readTree(Files.readString(exported.resolve("nuaforma-openapi.yaml"))))
    }

    @Test
    fun `actual capabilities assessment errors and DOCX attachments conform to the documented operations`() = testApplication {
        application { KtorServer(ProviderRegistry.discover(), ServerConfiguration(temporaryDirectory = temporary)).installRoutes(this) }
        val contract = mapper.readTree(client.get(ApiRoutes.OPENAPI_JSON).bodyAsText())
        val schemas = OpenApiSchemaChecks(contract)
        val capabilities = client.get(ApiRoutes.CAPABILITIES)
        assertEquals(HttpStatusCode.OK, capabilities.status)
        assertTrue(schemas.matches("Capabilities", mapper.readTree(capabilities.bodyAsText())))
        val example = contract.path("components").path("schemas").path("GenerationEnvelope").path("example")
        assertTrue(schemas.matches("GenerationEnvelope", example))
        val assessment = client.post(ApiRoutes.ASSESS) { contentType(ContentType.Application.Json); setBody(example.toString()) }
        assertEquals(HttpStatusCode.OK, assessment.status, assessment.bodyAsText())
        assertTrue(schemas.matches("Assessment", mapper.readTree(assessment.bodyAsText())))
        val generated = client.post(ApiRoutes.DOCUMENTS) { contentType(ContentType.MultiPart.FormData.withParameter("boundary", "OASCHECK")); setBody(multipart(example.toString())) }
        assertEquals(HttpStatusCode.OK, generated.status, generated.bodyAsText())
        assertTrue(contract.path("paths").path(ApiRoutes.DOCUMENTS).path("post").path("responses").path("200").path("content").has(generated.headers[HttpHeaders.ContentType]!!.substringBefore(';')))
        assertTrue(generated.bodyAsBytes().size > 0)
        assertEquals("attachment; filename=\"agreement.docx\"", generated.headers[HttpHeaders.ContentDisposition])
        val rejected = client.post(ApiRoutes.ASSESS) { contentType(ContentType.Application.Json); setBody("""{"input":{"format":"docx"},"output":{"format":"pdf","options":{"version":"9.9"}}}""") }
        assertTrue(contract.path("paths").path(ApiRoutes.ASSESS).path("post").path("responses").has(rejected.status.value.toString()))
        assertTrue(schemas.matches("ApiError", mapper.readTree(rejected.bodyAsText())))
        val invalid = client.post(ApiRoutes.ASSESS) { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertTrue(schemas.matches("ApiError", mapper.readTree(invalid.bodyAsText())))
        Files.list(temporary).use { assertEquals(0, it.count()) }
    }

    @Test
    fun `envelope schema and actual JSON decoder agree on representative valid and invalid boundaries`() {
        val contract = mapper.readTree(ApiDocumentation().jsonText)
        val checks = OpenApiSchemaChecks(contract)
        val codec = KotlinxJsonCodec()
        val examples = listOf(
            """{"input":{"format":"company+format"},"output":{"format":"custom"},"data":{"objects":[{"null":null,"decimal":1.25}],"true":true}}""" to true,
            """{"input":{"format":"docx"},"output":{"format":"pdf","options":{"version":"1.7","profile":"standard","paragraphDefaults":{"afterTwips":160},"fonts":[{"resource":"font1"}]}}}""" to true,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"options":{"watermark":null}}""" to true,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"filename":"../../bad"}""" to false,
            """{"input":{"format":"DOCX"},"output":{"format":"docx"}}""" to false,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"data":[]}""" to false,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"options":{"other":true}}""" to false,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"options":{"watermark":{"text":" ","opacity":0.5}}}""" to false,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"options":{"watermark":{"text":"A","opacity":1.01}}}""" to false,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"options":{"watermark":{"text":"A","rotation":-361}}}""" to false,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"options":{"watermark":{"text":"A","color":"blue"}}}""" to false,
            "{}" to false,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"options":{"watermark":{"text":"A"}}}""".replace("\"A\"", mapper.writeValueAsString("😀".repeat(2048))) to true,
            """{"input":{"format":"docx"},"output":{"format":"docx"},"options":{"watermark":{"text":"A"}}}""".replace("\"A\"", mapper.writeValueAsString("😀".repeat(2049))) to false,
        )
        examples.forEach { (source, expected) ->
            assertEquals(expected, checks.matches("GenerationEnvelope", mapper.readTree(source)), source)
            assertEquals(expected, runCatching { codec.decode(source.toByteArray()) }.isSuccess, source)
        }
        val parts = contract.path("paths").path(ApiRoutes.DOCUMENTS).path("post").path("requestBody").path("content").path("multipart/form-data").path("schema")
        val example = contract.path("components").path("schemas").path("GenerationEnvelope").path("example")
        val validParts = mapper.createObjectNode().apply { set<JsonNode>("request", example); put("template", "binary"); put("resource.font1", "binary") }
        assertTrue(checks.matches(parts, validParts))
        validParts.put("resource.../bad", "binary")
        assertFalse(checks.matches(parts, validParts))
        // A wrong contract must fail behavioral assertions instead of being accepted as a new golden result.
        val drifted = mapper.readTree(ApiDocumentation().jsonText.replace("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$", "^BROKEN$"))
        assertFalse(OpenApiSchemaChecks(drifted).matches("GenerationEnvelope", example))
    }

    private fun multipart(source: String): ByteArray = ByteArrayOutputStream().also { out ->
        fun part(name: String, type: String, bytes: ByteArray) {
            out.write("--OASCHECK\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"ignored\"\r\nContent-Type: $type\r\n\r\n".toByteArray())
            out.write(bytes); out.write("\r\n".toByteArray())
        }
        val template = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            mapOf("[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
                "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Hello {customer.name}</w:t></w:r></w:p><w:sectPr/></w:body></w:document>""")
                .forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        } }.toByteArray()
        part("request", "application/json", source.toByteArray())
        part("template", "application/octet-stream", template)
        out.write("--OASCHECK--\r\n".toByteArray())
    }.toByteArray()
}
