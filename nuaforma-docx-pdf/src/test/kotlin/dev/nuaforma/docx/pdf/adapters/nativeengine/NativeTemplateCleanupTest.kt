package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.*
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeTemplateCleanupTest {
    private val word = CleanupPackage.WORD
    private val rel = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun context() = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))

    private fun parts(): LinkedHashMap<String, ByteArray> = unpack(requireNotNull(javaClass.getResourceAsStream("/native-profile/word-letter.docx")).use { it.readBytes() })

    private fun unpack(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { parts ->
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
    }

    private fun pack(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun change(parts: MutableMap<String, ByteArray>, part: String, transform: (String) -> String) {
        parts[part] = transform(parts.getValue(part).decodeToString()).toByteArray()
    }

    private fun clean(source: ByteArray): Pair<ByteArray, TemplateCleanupReport> {
        val output = ByteArrayOutputStream()
        val report = NativeDocxPdfProvider(NativePdfConfiguration(emptyList())).cleanTemplate(source.inputStream(), output, context())
        val result = output.toByteArray()
        NativeDocxReader(context()) { _, _, _ -> "verified-structure" }.read(result)
        return result to report
    }

    @Test
    fun `supported templates are returned byte for byte without evaluating even malformed template markers`() {
        val source = parts()
        change(source, "word/document.xml") { it.replace("Geachte", "{#if customer} {#each rows} {name | trim()} {unclosed Geachte") }
        val bytes = pack(source)
        val (actual, report) = clean(bytes)
        assertArrayEquals(bytes, actual)
        assertTrue(report.changes.isEmpty())
    }

    @Test
    fun `qualified opaque JPEG previews remain byte exact during cleanup`() {
        val source = parts()
        source["docProps/thumbnail.jpeg"] = byteArrayOf(1, 2, 3)
        change(source, "_rels/.rels") { it.replace("</Relationships>", """<Relationship Id="thumbnail" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail" Target="docProps/thumbnail.jpeg"/></Relationships>""") }
        change(source, "[Content_Types].xml") { it.replace("</Types>", """<Override PartName="/docProps/thumbnail.jpeg" ContentType="image/jpeg"/></Types>""") }
        val bytes = pack(source)
        val (actual, report) = clean(bytes)
        assertArrayEquals(bytes, actual)
        assertTrue(report.changes.isEmpty(), report.changes.toString())
    }

    @Test
    fun `unsupported package parts and their package relationships are removed with complete reporting`() {
        val source = parts()
        source["docProps/thumbnail.gif"] = byteArrayOf(1, 2, 3)
        change(source, "_rels/.rels") { it.replace("</Relationships>", """<Relationship Id="thumbnail" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail" Target="docProps/thumbnail.gif"/></Relationships>""") }
        change(source, "[Content_Types].xml") { it.replace("</Types>", """<Override PartName="/docProps/thumbnail.gif" ContentType="image/gif"/></Types>""") }
        val bytes = pack(source)
        val snapshot = bytes.copyOf()
        val (actual, report) = clean(bytes)
        val output = unpack(actual)
        assertFalse(output.containsKey("docProps/thumbnail.gif"))
        assertFalse(output.getValue("_rels/.rels").decodeToString().contains("thumbnail"))
        assertFalse(output.getValue("[Content_Types].xml").decodeToString().contains("thumbnail"))
        assertArrayEquals(source.getValue("word/document.xml"), output.getValue("word/document.xml"))
        assertArrayEquals(snapshot, bytes)
        assertTrue(report.changes.any { it.action == TemplateCleanupAction.REMOVE_PART && it.part == "docProps/thumbnail.gif" })
        assertTrue(report.changes.any { it.part == "_rels/.rels" })
        assertTrue(report.changes.any { it.part == "[Content_Types].xml" })
    }

    @Test
    fun `unsupported visible controls are removed while surrounding template instructions remain literal`() {
        val source = parts()
        change(source, "word/document.xml") {
            it.replace("<w:body>", """<w:body><w:sdt><w:sdtContent><w:p><w:r><w:t>REMOVED_CONTROL</w:t></w:r></w:p></w:sdtContent></w:sdt><w:p><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/></w:rPr><w:t>{#if customer} {#each rows} {name} {unclosed</w:t></w:r></w:p>""")
        }
        val (actual, report) = clean(pack(source))
        val document = unpack(actual).getValue("word/document.xml").decodeToString()
        assertFalse(document.contains("REMOVED_CONTROL"))
        assertTrue(document.contains("{#if customer} {#each rows} {name} {unclosed"))
        assertTrue(report.changes.any { it.location?.endsWith("w:sdt[1]") == true })
        assertFalse(report.changes.any { it.reason.contains("REMOVED_CONTROL") })
        assertNotNull(Class.forName("org.docx4j.openpackaging.packages.WordprocessingMLPackage")
            .getMethod("load", java.io.InputStream::class.java).invoke(null, actual.inputStream()))
    }

    @Test
    fun `unsupported attributes are removed without erasing supported sibling values`() {
        val source = parts()
        val xml = ConversionXml(context())
        val root = xml.read(source.getValue("word/document.xml"))
        val paragraph = root.descendants().first { it.name == javax.xml.namespace.QName(word, "p") }
        val properties = paragraph.elements().firstOrNull { it.name == javax.xml.namespace.QName(word, "pPr") }
            ?: XmlElement(javax.xml.namespace.QName(word, "pPr", "w")).also { paragraph.children.add(0, it) }
        val spacing = properties.elements().firstOrNull { it.name == javax.xml.namespace.QName(word, "spacing") }
            ?: XmlElement(javax.xml.namespace.QName(word, "spacing", "w")).also { properties.children.add(it) }
        spacing.attributes[javax.xml.namespace.QName(word, "before", "w")] = "120"
        spacing.attributes[javax.xml.namespace.QName(word, "unsupported", "w")] = "1"
        source["word/document.xml"] = xml.write(root)
        val (actual, report) = clean(pack(source))
        val document = unpack(actual).getValue("word/document.xml").decodeToString()
        assertFalse(document.contains("unsupported="))
        assertTrue(document.contains("w:before=\"120\""))
        assertTrue(report.changes.any { it.action == TemplateCleanupAction.REMOVE_ATTRIBUTE })
    }

    @Test
    fun `missing required styles and section geometry become explicit supported defaults`() {
        val source = parts()
        source.remove("word/styles.xml")
        change(source, "word/document.xml") { it.replace(Regex("<w:sectPr[\\s\\S]*?</w:sectPr>"), "") }
        val (actual, report) = clean(pack(source))
        assertTrue(unpack(actual).getValue("word/styles.xml").decodeToString().contains("Liberation Sans"))
        assertTrue(report.changes.any { it.reason.contains("A4") })
        assertTrue(report.changes.any { it.action == TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE })
    }

    @Test
    fun `removing unsupported image parts also removes their visible objects and stale references`() {
        val source = parts()
        val xml = ConversionXml(context())
        val relations = xml.read(source.getValue("word/_rels/document.xml.rels"))
        val image = relations.elements().first { it.attribute("", "Type")?.endsWith("/image") == true }
        val old = java.net.URI("https://package.invalid/word/document.xml").resolve(image.attribute("", "Target")).path.removePrefix("/")
        source.remove(old)
        image.attributes[javax.xml.namespace.QName("Target")] = "media/unsupported.jpeg"
        source["word/media/unsupported.jpeg"] = byteArrayOf(1, 2, 3)
        source["word/_rels/document.xml.rels"] = xml.write(relations)
        val id = requireNotNull(image.attribute("", "Id"))
        val (actual, report) = clean(pack(source))
        val output = unpack(actual)
        assertFalse(output.containsKey("word/media/unsupported.jpeg"))
        assertFalse(output.getValue("word/document.xml").decodeToString().contains("r:embed=\"$id\""))
        assertTrue(report.changes.any { it.part == "word/document.xml" && it.reason.contains("object or reference") })
    }

    @Test
    fun `unsupported columns are removed and final section ordering is repaired without evaluating markers`() {
        val source = parts()
        val xml = ConversionXml(context())
        val root = xml.read(source.getValue("word/document.xml"))
        val section = root.descendants().last { it.name == javax.xml.namespace.QName(word, "sectPr") }
        val columns = section.elements().firstOrNull { it.name == javax.xml.namespace.QName(word, "cols") }
            ?: XmlElement(javax.xml.namespace.QName(word, "cols", "w")).also { section.children.add(it) }
        columns.attributes[javax.xml.namespace.QName(word, "num", "w")] = "2"
        val body = root.elements().single { it.name == javax.xml.namespace.QName(word, "body") }
        body.children += xml.read("""<w:p xmlns:w="$word"><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/></w:rPr><w:t>{literal}</w:t></w:r></w:p>""".toByteArray())
        source["word/document.xml"] = xml.write(root)
        val (actual, report) = clean(pack(source))
        val document = unpack(actual).getValue("word/document.xml").decodeToString()
        assertTrue(document.contains("{literal}"))
        assertFalse(document.contains("w:num=\"2\""))
        assertTrue(report.changes.any { it.action == TemplateCleanupAction.RESTORE_REQUIRED_STRUCTURE })
    }

    @Test
    fun `macro-enabled declarations and binary components are stripped without executing content`() {
        val source = parts()
        source["word/vbaProject.bin"] = "NEVER_EXECUTE".toByteArray()
        change(source, "[Content_Types].xml") {
            it.replace("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml", "application/vnd.ms-word.document.macroEnabled.main+xml")
        }
        val (actual, report) = clean(pack(source))
        val output = unpack(actual)
        assertFalse(output.containsKey("word/vbaProject.bin"))
        assertFalse(output.getValue("[Content_Types].xml").decodeToString().contains("macroEnabled"))
        assertTrue(report.changes.any { it.reason.contains("macro-enabled main-part") })
    }

    @Test
    fun `an unsupported root relationship is removed and the essential main part remains connected`() {
        val source = parts()
        change(source, "_rels/.rels") { it.replace("</Relationships>", """<Relationship Id="unsupported" Type="urn:unsupported" Target="word/styles.xml"/></Relationships>""") }
        val (actual, report) = clean(pack(source))
        val relations = unpack(actual).getValue("_rels/.rels").decodeToString()
        assertFalse(relations.contains("urn:unsupported"))
        assertTrue(relations.contains("officeDocument"))
        assertTrue(report.changes.any { it.part == "_rels/.rels" })
    }

    @Test
    fun `malformed optional XML can be removed without parsing or replacing template instructions`() {
        val source = parts()
        source["word/settings.xml"] = "<broken".toByteArray()
        change(source, "word/document.xml") { it.replace("Geachte", "{unchanged} Geachte") }
        val (actual, report) = clean(pack(source))
        val output = unpack(actual)
        assertFalse(output.containsKey("word/settings.xml"))
        assertTrue(output.getValue("word/document.xml").decodeToString().contains("{unchanged}"))
        assertTrue(report.changes.any { it.part == "word/settings.xml" && it.reason.contains("malformed XML") })
    }

    @Test
    fun `explicit internal relationship attributes can be stripped without dropping the referenced logo`() {
        val source = parts()
        val xml = ConversionXml(context())
        val relations = xml.read(source.getValue("word/_rels/document.xml.rels"))
        relations.elements().filter { it.attribute("", "TargetMode") == null }.forEach {
            it.attributes[javax.xml.namespace.QName("TargetMode")] = "Internal"
        }
        source["word/_rels/document.xml.rels"] = xml.write(relations)
        val (actual, report) = clean(pack(source))
        val output = unpack(actual)
        assertArrayEquals(source.getValue("word/document.xml"), output.getValue("word/document.xml"))
        assertFalse(output.getValue("word/_rels/document.xml.rels").decodeToString().contains("TargetMode=\"Internal\""))
        assertTrue(report.changes.any { it.action == TemplateCleanupAction.REMOVE_ATTRIBUTE && it.part.endsWith(".rels") })
    }

    @Test
    fun `a missing document relationship part is restored and dangling visible references are removed`() {
        val source = parts()
        source.remove("word/_rels/document.xml.rels")
        val (actual, report) = clean(pack(source))
        assertTrue(unpack(actual).containsKey("word/_rels/document.xml.rels"))
        assertTrue(report.changes.any { it.reason.contains("required document relationships") })
    }

    @Test
    fun `unsupported Unicode XML names use private namespace provenance without invalid public coordinates`() {
        val source = parts()
        change(source, "word/document.xml") { it.replace("<w:body>", "<w:body><w:неизвестно/>") }
        val (actual, report) = clean(pack(source))
        assertFalse(unpack(actual).getValue("word/document.xml").decodeToString().contains("неизвестно"))
        assertTrue(report.changes.any { it.action == TemplateCleanupAction.REMOVE_ELEMENT && it.part == "word/document.xml" })
    }

    @Test
    fun `archive entry and expansion limits cannot be bypassed by classifying failures as removable`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(emptyList()))
        val source = pack(parts())
        for (limits in listOf(GenerationLimits(maxArchiveEntries = 1), GenerationLimits(maxExpandedBytes = 16))) {
            val output = ByteArrayOutputStream()
            val context = GenerationContext(limits, CancellationToken.NONE, null, java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT))
            val error = assertThrows(GenerationException::class.java) { provider.cleanTemplate(source.inputStream(), output, context) }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, error.diagnostics.single().code)
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `archive stream and directory views cannot disagree about the template that was cleaned`() {
        val first = parts()
        change(first, "word/document.xml") { it.replace("Geachte", "{streamView} Geachte") }
        val second = parts()
        change(second, "word/document.xml") { it.replace("Geachte", "{wordView} Geachte") }
        val xml = ConversionXml(context())
        val root = xml.read(second.getValue("word/document.xml"))
        val section = root.descendants().last { it.name == javax.xml.namespace.QName(word, "sectPr") }
        val columns = section.elements().firstOrNull { it.name == javax.xml.namespace.QName(word, "cols") }
            ?: XmlElement(javax.xml.namespace.QName(word, "cols", "w")).also { section.children.add(it) }
        columns.attributes[javax.xml.namespace.QName(word, "num", "w")] = "2"
        second["word/document.xml"] = xml.write(root)
        val (actual, report) = clean(pack(first) + pack(second))
        val canonical = ByteArrayOutputStream()
        dev.nuaforma.docx.DocxDocuments.open(actual.inputStream(), context()).use { it.writeTo(canonical) }
        assertDoesNotThrow { NativeDocxReader(context()) { _, _, _ -> "word-directory-check" }.read(canonical.toByteArray()) }
        val document = unpack(actual).getValue("word/document.xml").decodeToString()
        assertTrue(document.contains("{wordView}"))
        assertFalse(document.contains("{streamView}"))
        assertTrue(report.changes.isNotEmpty())
    }

    @Test
    fun `a forged directory checksum cannot pass cleanup even when the stream checksum is valid`() {
        val source = pack(parts())
        val centralHeader = (0..source.size - 46).first { index ->
            source[index] == 0x50.toByte() && source[index + 1] == 0x4b.toByte() &&
                source[index + 2] == 0x01.toByte() && source[index + 3] == 0x02.toByte()
        }
        source[centralHeader + 16] = (source[centralHeader + 16].toInt() xor 1).toByte()
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            NativeDocxPdfProvider(NativePdfConfiguration(emptyList())).cleanTemplate(source.inputStream(), output, context())
        }
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, error.diagnostics.single().code)
        assertEquals(0, output.size())
    }

    @Test
    fun `cancellation and byte limits never publish partial output or leave admission occupied`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(emptyList(), maximumConcurrentConversions = 1))
        val bytes = pack(parts())
        val contexts = listOf(
            GenerationContext(GenerationLimits(), CancellationToken { true }, null, java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT)),
            GenerationContext(GenerationLimits(maxTemplateBytes = 8), CancellationToken.NONE, null, java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT)),
            GenerationContext(GenerationLimits(maxOutputBytes = 8), CancellationToken.NONE, null, java.time.Clock.systemUTC(), GenerationLogging(LoggingMode.DEVELOPMENT)))
        for (context in contexts) {
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) { provider.cleanTemplate(bytes.inputStream(), output, context) }
            assertTrue(error.diagnostics.any { it.code in setOf(DiagnosticCodes.CANCELLED, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) })
            assertEquals(0, output.size())
        }
        val output = ByteArrayOutputStream()
        provider.cleanTemplate(bytes.inputStream(), output, context())
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun `shared provider isolates concurrent documents and reports`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(emptyList(), maximumConcurrentConversions = 4))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val futures = (1..3).map { index -> executor.submit<Pair<ByteArray, TemplateCleanupReport>> {
                val source = parts()
                change(source, "word/document.xml") { it.replace("Geachte", "{request$index} Geachte") }
                source["docProps/thumbnail$index.jpeg"] = byteArrayOf(1)
                val output = ByteArrayOutputStream()
                val report = provider.cleanTemplate(pack(source).inputStream(), output, context())
                output.toByteArray() to report
            } }
            for ((index, future) in futures.withIndex()) {
                val (bytes, report) = future.get(30, java.util.concurrent.TimeUnit.SECONDS)
                val document = unpack(bytes).getValue("word/document.xml").decodeToString()
                assertTrue(document.contains("{request${index + 1}}"))
                assertTrue(report.changes.any { it.part == "docProps/thumbnail${index + 1}.jpeg" })
                assertFalse(report.changes.any { it.part.contains("thumbnail${(index + 1) % 3 + 1}.") })
            }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `production and malformed or unsafe archives cannot produce a cleaned output`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(emptyList()))
        val output = ByteArrayOutputStream()
        val production = assertThrows(GenerationException::class.java) {
            provider.cleanTemplate(pack(parts()).inputStream(), output, GenerationContext())
        }
        assertEquals(RejectionReason.TEMPLATE_DIAGNOSTICS_REQUIRE_DEVELOPMENT, production.diagnostics.single().rejectionReason)
        val unsafe = parts().also { it["../outside.xml"] = "x".toByteArray() }
        assertThrows(GenerationException::class.java) { provider.cleanTemplate(pack(unsafe).inputStream(), output, context()) }
        val malicious = parts().also { it["word/document.xml"] = """<!DOCTYPE w [<!ENTITY secret SYSTEM "file:///never-read">]><w:document xmlns:w="$word">&secret;</w:document>""".toByteArray() }
        assertThrows(GenerationException::class.java) { provider.cleanTemplate(pack(malicious).inputStream(), output, context()) }
        assertEquals(0, output.size())
    }
}
