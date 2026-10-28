package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxProvider
import dev.nuaforma.docx.pdf.ownengine.OwnEngineDocxReader
import dev.nuaforma.docx.pdf.ownengine.NativeVariableReport
import dev.nuaforma.pdf.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.ImageType
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.Color

class NativeDocxPdfProviderTest {
    @TempDir lateinit var temporary: Path
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    private fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
        NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
    }
    private fun requirements(profile: String = "standard") = GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
        FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))

    private fun validator() = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
        Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)

    private fun repack(bytes: ByteArray, change: (String, ByteArray) -> ByteArray): ByteArray = ByteArrayOutputStream().also { result ->
        ZipOutputStream(result).use { output -> ZipInputStream(bytes.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                output.putNextEntry(ZipEntry(entry.name))
                output.write(change(entry.name, input.readBytes()))
                output.closeEntry()
            }
        } }
    }.toByteArray()

    private fun render(provider: NativeDocxPdfProvider, source: ByteArray, data: Map<String, Any?> = emptyMap(), profile: String = "standard"): ByteArray =
        ByteArrayOutputStream().also { provider.generate(GenerationRequest(source.inputStream(), requirements(profile), data), it, GenerationContext()) }.toByteArray()

    @Test fun `mixed quotation date expressions retain literal reference text and page pixels`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        val fixture = resource("/own-engine/source.docx")
        fun document(text: String) = repack(fixture) { name, bytes ->
            if (name != "word/document.xml") bytes else bytes.decodeToString().replace("<w:body>",
                "<w:body><w:p><w:r><w:rPr><w:rFonts w:ascii=\"Liberation Sans\" w:hAnsi=\"Liberation Sans\"/></w:rPr><w:t>$text</w:t></w:r></w:p>").toByteArray()
        }
        Loader.loadPDF(render(provider, document("28-10-2026"))).use { expected ->
            assertTrue(PDFTextStripper().getText(expected).contains("28-10-2026"))
            val wideName = "longDateField".repeat(20)
            for (expression in listOf("{day | date(\"dd-MM-yyyy\")}", "{day | date('dd-MM-yyyy')}",
                "{day | date(“dd-MM-yyyy”)}", "{day | date(‘dd-MM-yyyy\")}", "{$wideName | date(“dd-MM-yyyy”)}")) {
                Loader.loadPDF(render(provider, document(expression), mapOf("day" to "2026-10-28", wideName to "2026-10-28"))).use { actual ->
                    assertEquals(expected.numberOfPages, actual.numberOfPages)
                    assertEquals(PDFTextStripper().getText(expected), PDFTextStripper().getText(actual))
                    for (page in 0 until expected.numberOfPages) {
                        val before = PDFRenderer(expected).renderImageWithDPI(page, 96f, ImageType.RGB)
                        val after = PDFRenderer(actual).renderImageWithDPI(page, 96f, ImageType.RGB)
                        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
                        assertArrayEquals(before.getRGB(0, 0, before.width, before.height, null, 0, before.width),
                            after.getRGB(0, 0, after.width, after.height, null, 0, after.width), expression)
                        if (expression.startsWith("{day | date(‘")) {
                            val directory = Files.createDirectories(Path.of("build/144-quotation-reference"))
                            ImageIO.write(before, "png", directory.resolve("literal-${page + 1}.png").toFile())
                            ImageIO.write(after, "png", directory.resolve("expression-${page + 1}.png").toFile())
                        }
                    }
                }
            }
        }
    }

    @Test fun `passive template references retain all text embedded fonts and pixels on branded and multipage documents`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        for (fixture in listOf("/own-engine/source.docx", "/own-engine-report/source.docx")) {
            val source = resource(fixture)
            Loader.loadPDF(render(provider, source)).use { expected ->
                for (style in listOf(null, "false", "off", "0")) {
                    val attached = attachedTemplate(source, style)
                    val snapshot = attached.copyOf()
                    Loader.loadPDF(render(provider, attached)).use { actual ->
                        assertArrayEquals(snapshot, attached)
                        assertEquals(expected.numberOfPages, actual.numberOfPages)
                        assertEquals(PDFTextStripper().getText(expected), PDFTextStripper().getText(actual))
                        val before = PDFRenderer(expected)
                        val after = PDFRenderer(actual)
                        for (page in 0 until expected.numberOfPages) {
                            assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                            val a = before.renderImageWithDPI(page, 96f, ImageType.RGB)
                            val b = after.renderImageWithDPI(page, 96f, ImageType.RGB)
                            assertEquals(a.width, b.width); assertEquals(a.height, b.height)
                            assertArrayEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), b.getRGB(0, 0, b.width, b.height, null, 0, b.width), "$fixture page ${page + 1} style=$style")
                            if (style == null) {
                                val review = Files.createDirectories(Path.of("build/passive-template-reference"))
                                ImageIO.write(a, "png", review.resolve("${fixture.substringBeforeLast('/').substringAfterLast('/')}-before-${page + 1}.png").toFile())
                                ImageIO.write(b, "png", review.resolve("${fixture.substringBeforeLast('/').substringAfterLast('/')}-after-${page + 1}.png").toFile())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun attachedTemplate(source: ByteArray, style: String?): ByteArray {
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                parts[entry.name] = input.readBytes()
            }
        }
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        if ("word/settings.xml" !in parts) {
            parts["word/settings.xml"] = """<w:settings xmlns:w="$word"/>""".toByteArray()
            parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").decodeToString().replace("</Types>",
                """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>""").toByteArray()
            parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").decodeToString().replace("</Relationships>",
                """<Relationship Id="settings139" Type="$rel/settings" Target="settings.xml"/></Relationships>""").toByteArray()
        }
        val settings = parts.getValue("word/settings.xml").decodeToString().replace("<w:settings xmlns:w=\"$word\"/>", "<w:settings xmlns:w=\"$word\"></w:settings>")
        parts["word/settings.xml"] = settings.replace("</w:settings>", """<w:attachedTemplate xmlns:r="$rel" r:id="template139"/>""" +
            style?.let { """<w:linkStyles w:val="$it"/>""" }.orEmpty() + "</w:settings>").toByteArray()
        parts["word/_rels/settings.xml.rels"] = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="template139" Type="$rel/attachedTemplate" Target="https://missing.invalid/template.dotm" TargetMode="External"/></Relationships>""".toByteArray()
        return ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { output ->
            for ((name, content) in parts) { output.putNextEntry(ZipEntry(name)); output.write(content); output.closeEntry() }
        } }.toByteArray()
    }

    @Test fun `Word-saved DOCX preserves text and layout after safe authoring metadata interpretation`() {
        val source = resource("/native-profile/word-letter.docx")
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        Loader.loadPDF(render(provider, source)).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertTrue(PDFTextStripper().getText(pdf).contains("Bevestiging van uw aanvraag"))
            assertEquals(1, pdf.getPage(0).resources.xObjectNames.count())
        }
        val invalid = repack(source) { name, bytes -> if (name == "word/document.xml")
            bytes.toString(Charsets.UTF_8).replace("<w:cols w:space=\"708\"/>", "<w:cols w:num=\"2\"/>").toByteArray() else bytes }
        assertFalse(source.contentEquals(invalid))
        val failure = assertThrows(GenerationException::class.java) { render(provider, invalid) }
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, failure.diagnostics.single().code)
    }

    @Test fun `RTL metadata never discards a distinct complex-script font for Latin text or digits`() {
        val source = repack(resource("/own-engine/source.docx")) { name, bytes -> when (name) {
            "word/document.xml" -> bytes.toString(Charsets.UTF_8).replace("<w:pPr>", "<w:pPr><w:bidi/>").toByteArray()
            "word/styles.xml" -> bytes.toString(Charsets.UTF_8).replace("w:hAnsi=\"Liberation Sans\"", "w:hAnsi=\"Liberation Sans\" w:cs=\"Arial\"").toByteArray()
            else -> bytes
        } }
        val failure = assertThrows(GenerationException::class.java) {
            render(NativeDocxPdfProvider(NativePdfConfiguration(fonts(), PdfConformanceValidator { _, _ -> })), source)
        }
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, failure.diagnostics.single().code)
        assertTrue(failure.cause?.message.orEmpty().contains("Separate complex-script font faces"))
    }

    @Test fun `registered provider uses native generation with explicit configuration`() {
        val provider = DocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        val output = ByteArrayOutputStream()
        provider.generate(GenerationRequest(resource("/own-engine/source.docx").inputStream(), requirements()), output, GenerationContext())
        Loader.loadPDF(output.toByteArray()).use { pdf -> assertEquals(1, pdf.numberOfPages) }
    }

    @Test fun `PNG and JPEG image pipes produce embedded images through the native provider`() {
        val source = repack(resource("/own-engine/source.docx")) { name, bytes -> if (name == "word/document.xml")
            bytes.toString(Charsets.UTF_8).replace("<w:sectPr>", "<w:p><w:pPr><w:spacing w:line=\"400\" w:lineRule=\"exact\"/></w:pPr><w:r><w:t>{picture | image(10, 5)}</w:t></w:r></w:p><w:sectPr>").toByteArray() else bytes }
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        val jpeg = resource("/native-profile/image-jpeg.base64").toString(Charsets.US_ASCII).trim()
        val sourcePng = java.util.zip.ZipInputStream(resource("/own-engine/source.docx").inputStream()).use { input ->
            var result: ByteArray? = null
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name.endsWith(".png")) result = input.readBytes()
            }
            java.util.Base64.getEncoder().encodeToString(requireNotNull(result))
        }
        for (picture in listOf(sourcePng, jpeg)) Loader.loadPDF(render(provider, source, mapOf("picture" to picture))).use { pdf ->
            assertEquals(2, pdf.getPage(0).resources.xObjectNames.count())
            assertFalse(PDFTextStripper().getText(pdf).contains("picture"))
        }
    }

    @Test fun `operator validation opt-out still performs actual structural validation`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator(), pdfaValidation = false))
        assertFalse(provider.conformancePolicies().single().validationEnabled)
        val bytes = render(provider, resource("/own-engine/source.docx"), profile = "pdfa-3b")
        Loader.loadPDF(bytes).use { pdf -> assertNotNull(pdf.documentCatalog.metadata) }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `image-only archival document needs no invented font`() {
        val source = repack(resource("/own-engine/source.docx")) { name, bytes -> if (name == "word/document.xml") {
            val xml = bytes.toString(Charsets.UTF_8)
            (xml.substringBefore("<w:body>") + "<w:body>" + xml.substringAfter("<w:body>").substringBefore("</w:p>") + "</w:p><w:sectPr>" +
                xml.substringAfter("<w:sectPr>")).toByteArray()
        } else bytes }
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(emptyList(), validator()))
        Loader.loadPDF(render(provider, source, profile = "pdfa-3b")).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertEquals(0, pdf.getPage(0).resources.fontNames.count())
            assertEquals(1, pdf.getPage(0).resources.xObjectNames.count())
        }
    }

    @Test fun `expanded conditional table rows paginate without losing or duplicating data`() {
        val source = NativeVariableReport.template(resource("/own-engine-report/source.docx"))
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        Loader.loadPDF(render(provider, source, NativeVariableReport.data(80))).use { pdf ->
            assertTrue(pdf.numberOfPages > 3)
            val text = PDFTextStripper().getText(pdf)
            for (index in 1..80) assertEquals(1, Regex("R${String.format(java.util.Locale.ROOT, "%03d", index)}\\b").findAll(text).count())
            assertTrue(text.contains("Niet van toepassing"))
            assertFalse(text.contains("{#each"))
            assertTrue((0 until pdf.numberOfPages).all { pdf.getPage(it).mediaBox.width == 595.3f })
        }
    }

    @Test fun `request fonts requiring full embedding pass actual archival validation`() {
        val font = resource("/own-engine-fonts/LiberationSans-Regular.ttf")
        val buffer = java.nio.ByteBuffer.wrap(font).order(java.nio.ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort(4).toInt() and 65535
        val table = (0 until count).first { buffer.getInt(12 + it * 16) == 0x4F532F32 }
        buffer.putShort(buffer.getInt(12 + table * 16 + 8) + 8, 256.toShort())
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        val output = ByteArrayOutputStream()
        val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF, mapOf(
            "profile" to "pdfa-3b", "fontMode" to "embed", "fonts" to listOf(mapOf("family" to "Liberation Sans", "face" to "regular", "resource" to "full-font")))))
        provider.generate(GenerationRequest(resource("/own-engine/source.docx").inputStream(), requirements,
            resources = mapOf("full-font" to ResourceSource { font.inputStream() })), output, GenerationContext())
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            assertTrue(pdf.getPage(0).resources.fontNames.map { pdf.getPage(0).resources.getFont(it) }.any { it.isEmbedded && it.name == "LiberationSans" })
        }
    }

    @Test fun `plain text paginates when widow control and keep rules are disabled`() {
        val source = repack(resource("/own-engine/source.docx")) { name, bytes ->
            val xml = bytes.toString(Charsets.UTF_8)
            when (name) {
                "word/styles.xml" -> xml.replace("<w:pPr><w:spacing", "<w:pPr><w:widowControl w:val=\"0\"/><w:spacing").toByteArray()
                "word/document.xml" -> xml.replace("<w:sectPr>", (1..120).joinToString("") { index ->
                    "<w:p><w:r><w:t>PLAIN${String.format(java.util.Locale.ROOT, "%03d", index)}</w:t></w:r></w:p>"
                } + "<w:sectPr>").toByteArray()
                else -> bytes
            }
        }
        Loader.loadPDF(render(NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator())), source)).use { pdf ->
            assertTrue(pdf.numberOfPages > 1)
            val text = PDFTextStripper().getText(pdf)
            for (index in 1..120) assertEquals(1, Regex("PLAIN${String.format(java.util.Locale.ROOT, "%03d", index)}\\b").findAll(text).count())
        }
    }

    @Test fun `all four profiles pass actual independent validation after template processing`() {
        val source = repack(resource("/own-engine/source.docx")) { name, bytes ->
            if (!name.endsWith(".png")) bytes else {
                val input = ImageIO.read(bytes.inputStream())
                val opaque = BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_RGB)
                val graphics = opaque.createGraphics()
                try { graphics.color = Color.WHITE; graphics.fillRect(0, 0, input.width, input.height); graphics.drawImage(input, 0, 0, null) }
                finally { graphics.dispose() }
                ByteArrayOutputStream().also { ImageIO.write(opaque, "png", it) }.toByteArray()
            }
        }
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator()))
        for (profile in PdfOutputProfile.entries) {
            val bytes = render(provider, source, profile = profile.id)
            Loader.loadPDF(bytes).use { pdf ->
                assertEquals(if (profile == PdfOutputProfile.PDFA_1B) 1.4f else 1.7f, pdf.version)
                assertTrue(PDFTextStripper().getText(pdf).contains("NF-2026-0057"))
            }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }

    @Test fun `template expansion remains isolated across concurrent native conversions`() {
        val source = repack(resource("/own-engine/source.docx")) { name, bytes ->
            if (name == "word/document.xml") bytes.toString(Charsets.UTF_8).replace("NF-2026-0057", "{#if active}{reference}{#else}HIDDEN{/if}").toByteArray() else bytes
        }
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), PdfConformanceValidator { _, _ -> }, 3))
        val executor = Executors.newFixedThreadPool(3)
        try {
            val futures = (1..9).map { index -> executor.submit<String> {
                Loader.loadPDF(render(provider, source, mapOf("active" to true, "reference" to "REQUEST-$index-END"))).use { PDFTextStripper().getText(it) }
            } }
            for ((index, future) in futures.withIndex()) {
                val text = future.get(60, TimeUnit.SECONDS)
                assertTrue(text.contains("REQUEST-${index + 1}-END"))
                assertFalse(text.contains("HIDDEN"))
                assertEquals(1, Regex("REQUEST-[0-9]+-END").findAll(text).count())
            }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun `admission rejects excess work and recovers after failure`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(true)
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), PdfConformanceValidator { _, _ ->
            if (block.getAndSet(false)) { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)); throw PdfRenderException(PdfFailureCode.VALIDATION_FAILED, "Negative control") }
        }, 1))
        val executor = Executors.newSingleThreadExecutor()
        val source = resource("/own-engine/source.docx")
        try {
            val first = executor.submit { assertThrows(GenerationException::class.java) { render(provider, source) } }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            val failure = assertThrows(GenerationException::class.java) { render(provider, source) }
            assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, failure.diagnostics.single().code)
            release.countDown()
            first.get(30, TimeUnit.SECONDS)
            assertTrue(render(provider, source).size > 100)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun `native provider preserves reviewed report layout through real DOCX template processing`() {
        val source = resource("/own-engine-report/source.docx")
        val expected = PdfEngine().render(OwnEngineDocxReader().read(source), listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) ->
            PdfFont(id, resource("/own-engine-fonts/LiberationSans-$face.ttf"))
        })
        val actual = render(NativeDocxPdfProvider(NativePdfConfiguration(fonts(), validator())), source)
        Loader.loadPDF(expected).use { before -> Loader.loadPDF(actual).use { after ->
            assertEquals(3, after.numberOfPages)
            assertEquals(PDFTextStripper().getText(before), PDFTextStripper().getText(after))
            val expectedRenderer = PDFRenderer(before)
            val actualRenderer = PDFRenderer(after)
            for (page in 0 until before.numberOfPages) {
                val a = expectedRenderer.renderImageWithDPI(page, 96f, ImageType.RGB)
                val b = actualRenderer.renderImageWithDPI(page, 96f, ImageType.RGB)
                assertEquals(a.width, b.width); assertEquals(a.height, b.height)
                assertArrayEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), b.getRGB(0, 0, b.width, b.height, null, 0, b.width), "Report page ${page + 1}")
            }
        } }
    }

    @Test fun `filled DOCX is accepted by the bounded production reader`() {
        val output = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(resource("/own-engine/source.docx").inputStream(),
            GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))), output, GenerationContext())
        val model = OwnEngineDocxReader().read(output.toByteArray())
        assertTrue(model.blocks.isNotEmpty())
    }

    @Test fun `native provider generates searchable PDF with branding and embedded fonts without LibreOffice`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), PdfConformanceValidator { _, _ -> }))
        val output = ByteArrayOutputStream()
        val result = provider.generate(GenerationRequest(resource("/own-engine/source.docx").inputStream(), requirements()), output, GenerationContext())
        assertEquals("standard", result.output.options["profile"])
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertTrue(PDFTextStripper().getText(pdf).contains("Bevestiging van uw aanvraag"))
            assertEquals(1, pdf.getPage(0).resources.xObjectNames.count())
            assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
        }
    }

    @Test fun `archival default is retained and unavailable without an independent validator`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts()))
        assertEquals(AssessmentStatus.UNAVAILABLE, provider.assess(requirements("pdfa-3b")).status)
        assertEquals("pdfa-3b", provider.capabilities().outputOptions.getValue(DocumentFormat.PDF).single { it.name == "profile" }.defaultValue)
    }

    @Test fun `failure and limits leave caller output untouched`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), PdfConformanceValidator { _, _ -> }))
        for (context in listOf(GenerationContext(GenerationLimits(maxOutputBytes = 32)), GenerationContext(cancellation = CancellationToken { true }))) {
            val output = ByteArrayOutputStream()
            val failure = assertThrows(GenerationException::class.java) {
                provider.generate(GenerationRequest(resource("/own-engine/source.docx").inputStream(), requirements()), output, context)
            }
            assertTrue(failure.diagnostics.any { it.code in setOf(DiagnosticCodes.CANCELLED, DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED) })
            assertEquals(0, output.size())
        }
    }

    @Test fun `DOCX output delegates unchanged and native options reject unqualified behavior`() {
        val provider = NativeDocxPdfProvider(NativePdfConfiguration(fonts(), PdfConformanceValidator { _, _ -> }))
        val output = ByteArrayOutputStream()
        val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
        provider.generate(GenerationRequest(resource("/own-engine/source.docx").inputStream(), requirements), output, GenerationContext())
        assertEquals('P'.code.toByte(), output.toByteArray()[0])
        assertEquals(AssessmentStatus.UNSUPPORTED, provider.assess(GenerationRequirements(requirements.input,
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard", "version" to "1.4")))).status)
    }
}
