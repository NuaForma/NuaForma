package dev.nuaforma.docx

import com.sun.net.httpserver.HttpServer
import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PassiveTemplateTest {
    private val word = SyntheticDocx.W
    private val office = SyntheticDocx.R
    private val rels = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val settings = "word/settings.xml"
    private val relationships = "word/_rels/settings.xml.rels"

    private fun parts(target: String = "file:///missing/PRIVATE_TEMPLATE.dotm", style: String = "") = SyntheticDocx.parts().apply {
        this[settings] = getValue(settings).decodeToString().replace("</w:settings>",
            """<w:attachedTemplate xmlns:r="$office" r:id="template"/>$style</w:settings>""").toByteArray()
        this[relationships] = """<Relationships xmlns="$rels"><Relationship Id="template" Type="$office/attachedTemplate" Target="$target" TargetMode="External"/></Relationships>""".toByteArray()
    }

    private fun roundTrip(source: ByteArray) = ByteArrayOutputStream().also { output ->
        DocxDocuments.open(source.inputStream()).use { it.writeTo(output) }
    }.toByteArray()

    @Test
    fun `passive file UNC HTTP and HTTPS attachments never load and preserve internal content`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> requests.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close() }
        server.start()
        try {
            val targets = listOf("file:///missing/PRIVATE_TEMPLATE.dotm", "file://missing.invalid/share/template.dotx",
                "\\\\missing.invalid\\share\\template.dotx", "https://missing.invalid/template.dotx", "http://127.0.0.1:${server.address.port}/template.dotm")
            for (target in targets) for (value in listOf(null, "false", "off", "0")) {
                val source = SyntheticDocx.zip(parts(target, value?.let { """<w:linkStyles w:val="$it"/>""" }.orEmpty()))
                val snapshot = source.copyOf()
                val actual = SyntheticDocx.unzip(roundTrip(source))
                assertArrayEquals(snapshot, source)
                for ((name, expected) in SyntheticDocx.parts()) {
                    if (name.endsWith(".xml") || name.endsWith(".rels")) assertEquals(SyntheticDocx.semantic(expected), SyntheticDocx.semantic(actual.getValue(name)), name)
                    else assertArrayEquals(expected, actual.getValue(name), name)
                }
                assertFalse(actual.getValue(settings).decodeToString().contains("attachedTemplate"))
                assertFalse(actual[relationships]?.decodeToString().orEmpty().contains("PRIVATE_TEMPLATE"))
            }
            assertEquals(0, requests.get())
        } finally { server.stop(0) }
    }

    @Test
    fun `automatic updates reject before loading and diagnostics never reveal the template target`() {
        for (attribute in listOf("", " w:val=\"true\"", " w:val=\"on\"", " w:val=\"1\"")) {
            reject(parts(style = "<w:linkStyles$attribute/>"), RejectionReason.EXTERNAL_TEMPLATE_STYLE_UPDATES_NOT_SUPPORTED)
        }
    }

    @Test
    fun `malformed duplicate dangling misplaced and forged references cannot bypass validation`() {
        val cases = listOf<Pair<String, (String) -> String>>(
            settings to { it.replace("r:id=\"template\"", "r:id=\"missing\"") },
            settings to { it.replace("r:id=\"template\"", "") },
            settings to { it.replace("r:id=\"template\"", "r:id=\"template\" w:val=\"extra\"") },
            settings to { it.replace("</w:settings>", "<w:attachedTemplate xmlns:r=\"$office\" r:id=\"template\"/></w:settings>") },
            settings to { it.replace("</w:settings>", "<w:linkStyles w:val=\"false\"/><w:linkStyles w:val=\"false\"/></w:settings>") },
            settings to { it.replace("</w:settings>", "<w:linkStyles w:val=\"invalid\"/></w:settings>") },
            settings to { it.replace("</w:settings>", "<w:linkStyles xmlns:x=\"urn:wrong\" x:val=\"false\"/></w:settings>") },
            settings to { it.replace("r:id=\"template\"/>", "r:id=\"template\">PRIVATE_TEMPLATE</w:attachedTemplate>") },
            settings to { it.replace("r:id=\"template\"/>", "r:id=\"template\"><w:zoom/></w:attachedTemplate>") },
            relationships to { it.replace(" TargetMode=\"External\"", "") },
            relationships to { it.replace(" TargetMode=\"External\"", " TargetMode=\"Internal\"") },
            relationships to { it.replace("TargetMode=\"External\"", "TargetMode=\"External\" Extra=\"unknown\"") },
            relationships to { it.replace("Target=\"file:///missing/PRIVATE_TEMPLATE.dotm\"", "Target=\"\"") },
            "word/document.xml" to { it.replace("<w:body>", "<w:body><w:attachedTemplate xmlns:r=\"$office\" r:id=\"template\"/>") },
            "word/_rels/document.xml.rels" to { it.replace(Regex("<Relationship[^>]*Type=\"$office/settings\"[^>]*/>"), "") },
            "word/_rels/document.xml.rels" to { it.replace(Regex("<Relationship[^>]*Type=\"$office/settings\"[^>]*/>")) { match -> "<Extra>${match.value}</Extra>" } },
        )
        for ((part, mutation) in cases) reject(parts().apply { this[part] = mutation(getValue(part).decodeToString()).toByteArray() }, RejectionReason.INVALID_ATTACHED_TEMPLATE_REFERENCE)
        reject(parts().apply { remove(relationships) }, RejectionReason.INVALID_ATTACHED_TEMPLATE_REFERENCE)
        reject(parts().apply { this[settings] = SyntheticDocx.parts().getValue(settings) }, RejectionReason.INVALID_ATTACHED_TEMPLATE_REFERENCE)
        reject(parts().apply { this[relationships] = getValue(relationships).decodeToString().replace("$office/attachedTemplate", "urn:forged/attachedTemplate").toByteArray() }, RejectionReason.LINKED_TEMPLATE_RELATIONSHIP)
        reject(parts().apply { this[relationships] = getValue(relationships).decodeToString().replace("</Relationships>", """<Relationship Id="template" Type="$office/attachedTemplate" Target="file:///missing" TargetMode="External"/></Relationships>""").toByteArray() }, RejectionReason.DUPLICATE_RELATIONSHIP_ID)
        reject(parts().apply { this["word/_rels/document.xml.rels"] = getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>", """<Relationship Id="settingsAgain" Type="$office/settings" Target="settings.xml"/></Relationships>""").toByteArray() }, RejectionReason.INVALID_ATTACHED_TEMPLATE_REFERENCE)
        reject(parts().apply { this["word/vbaProject.bin"] = byteArrayOf(1) }, RejectionReason.MACROS_EMBEDDED_OBJECTS_AND_SIGNED_PACKAGES_ARE_NOT_SUPPORTED)
        reject(parts().apply { this["word/_rels/document.xml.rels"] = getValue("word/_rels/document.xml.rels").decodeToString().replace("media/mark.png", "file:///PRIVATE_TEMPLATE").replace("Type=\"$office/image\"", "Type=\"$office/image\" TargetMode=\"External\"").toByteArray() }, RejectionReason.EXTERNAL_RELATIONSHIPS_OTHER_THAN_PASSIVE_HYPERLINKS_ARE_NOT_SUPPORTED)
    }

    @Test
    fun `unrelated settings relationships survive and disabled updates without an attachment are inert`() {
        val source = parts(style = "<w:linkStyles w:val=\"false\"/>").apply {
            this[relationships] = getValue(relationships).decodeToString().replace("</Relationships>",
                """<Relationship Id="other" Type="$office/hyperlink" Target="https://example.invalid/unchanged" TargetMode="External"/></Relationships>""").toByteArray()
        }
        val actual = SyntheticDocx.unzip(roundTrip(SyntheticDocx.zip(source)))
        assertTrue(actual.getValue(relationships).decodeToString().contains("https://example.invalid/unchanged"))
        assertFalse(actual.getValue(relationships).decodeToString().contains("attachedTemplate"))
        val disabled = SyntheticDocx.parts().apply { this[settings] = getValue(settings).decodeToString().replace("</w:settings>", "<w:linkStyles w:val=\"0\"/></w:settings>").toByteArray() }
        assertFalse(SyntheticDocx.unzip(roundTrip(SyntheticDocx.zip(disabled))).getValue(settings).decodeToString().contains("linkStyles"))
    }

    @Test
    fun `UTF16 metadata and resource budgets remain explicit during detachment`() {
        val source = parts().apply {
            for (name in listOf(settings, relationships)) this[name] = ("<?xml version=\"1.0\" encoding=\"UTF-16\"?>" +
                getValue(name).decodeToString()).toByteArray(StandardCharsets.UTF_16)
        }
        val bytes = SyntheticDocx.zip(source)
        assertFalse(SyntheticDocx.unzip(roundTrip(bytes)).getValue(settings).decodeToString().contains("attachedTemplate"))
        for (context in listOf(GenerationContext(GenerationLimits(maxExpandedBytes = 100)),
            GenerationContext(cancellation = CancellationToken { true }))) {
            assertThrows(GenerationException::class.java) { DocxDocuments.open(bytes.inputStream(), context).use { } }
        }
    }

    @Test
    fun `concurrent generation detaches only private packages`() {
        val source = SyntheticDocx.zip(parts())
        val snapshot = source.copyOf()
        val provider = DocxProvider()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..12).map { index -> Callable {
                val output = ByteArrayOutputStream()
                provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)),
                    mapOf("customer" to mapOf("name" to "Tenant $index"), "reference" to "NF-$index", "active" to true, "groups" to emptyList<Any>())), output, GenerationContext())
                val result = SyntheticDocx.unzip(output.toByteArray())
                val body = result.getValue("word/document.xml").decodeToString()
                assertTrue(body.contains("Tenant $index"))
                assertFalse(result.getValue(settings).decodeToString().contains("attachedTemplate"))
            } }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally { executor.shutdownNow() }
        assertArrayEquals(snapshot, source)
    }

    private fun reject(source: Map<String, ByteArray>, expected: RejectionReason) {
        val diagnostic = assertThrows(GenerationException::class.java) { DocxDocuments.open(SyntheticDocx.zip(source).inputStream()).use { } }.diagnostics.single()
        assertEquals(expected, diagnostic.rejectionReason)
        assertFalse(diagnostic.message.contains("PRIVATE_TEMPLATE"))
        assertFalse(diagnostic.rejectionReason!!.remediation.contains("PRIVATE_TEMPLATE"))
    }
}
