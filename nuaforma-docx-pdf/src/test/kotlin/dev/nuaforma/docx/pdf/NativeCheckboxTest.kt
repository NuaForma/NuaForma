package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.DocxProvider
import dev.nuaforma.image.comparison.kotlin.assertImagesMatch
import dev.nuaforma.pdf.PdfOutputProfile
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class NativeCheckboxTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `rendered checkbox strokes follow normal and bold effective text weight`() {
        val directory = Files.createDirectories(Path.of("build/149-checkbox/preview"))
        val data = mapOf("yes" to true, "no" to false, "gender" to "Vrouw")
        val input = source(false)
        Files.write(directory.resolve("template.docx"), input)
        val docx = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(input.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX)), data), docx, GenerationContext())
        Files.write(directory.resolve("actual.docx"), docx.toByteArray())
        val output = ByteArrayOutputStream()
        provider().generate(GenerationRequest(input.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF)), data), output, GenerationContext())
        Files.write(directory.resolve("native.pdf"), output.toByteArray())
        Loader.loadPDF(output.toByteArray()).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val tokens = PDFStreamParser(pdf.getPage(0)).parse()
            val widths = tokens.withIndex().filter { (_, token) -> token is Operator && token.name == "w" }
                .map { (index, _) -> (tokens[index - 1] as org.apache.pdfbox.cos.COSNumber).floatValue() }
            assertTrue(widths.any { kotlin.math.abs(it - .616f) < .001f }, "Normal 11pt text uses a 70-unit stroke")
            assertTrue(widths.any { kotlin.math.abs(it - .88f) < .001f }, "Bold 11pt text uses a 100-unit stroke")
            ImageIO.write(PDFRenderer(pdf).renderImageWithDPI(0, 144f), "png", directory.resolve("preview.png").toFile())
            ImageIO.write(PDFRenderer(pdf).renderImageWithDPI(0, 96f), "png", directory.resolve("page-1.png").toFile())
        }
        val provider = provider()
        for (letter in listOf(false, true)) candidate(report(letter), data,
            directory.resolve(if (letter) "report-letter" else "report-a4"), provider)
    }

    /** Review output stays in build; this helper never writes golden resources. */
    private fun candidate(source: ByteArray, data: Map<String, *>, directory: Path, provider: DocxPdfProvider) {
        Files.createDirectories(directory)
        val docx = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.DOCX)), data), docx, GenerationContext())
        Files.write(directory.resolve("actual.docx"), docx.toByteArray())
        val pdf = ByteArrayOutputStream()
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF)), data), pdf, GenerationContext())
        Files.write(directory.resolve("native.pdf"), pdf.toByteArray())
        Loader.loadPDF(pdf.toByteArray()).use { document ->
            assertEquals(3, document.numberOfPages)
            for (page in 0 until document.numberOfPages)
                ImageIO.write(PDFRenderer(document).renderImageWithDPI(page, 96f), "png", directory.resolve("page-${page + 1}.png").toFile())
        }
    }


    @Test
    fun `unqualified vector text decorations fail explicitly without publishing PDF bytes`() {
        val provider = provider()
        for (decoration in listOf("""<w:u w:val="single"/>""", "<w:strike/>", """<w:vertAlign w:val="superscript"/>""")) {
            val parts = PdfFixture.unzip(source(false)).toMutableMap()
            parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
                .replaceFirst("<w:rPr>", "<w:rPr>$decoration").toByteArray()
            val output = ByteArrayOutputStream()
            val error = assertThrows(GenerationException::class.java) {
                provider.generate(GenerationRequest(PdfFixture.zip(parts).inputStream(), GenerationRequirements(
                    FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.PDF)),
                    mapOf("yes" to true, "no" to false, "gender" to "Vrouw")), output, GenerationContext())
            }
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, error.diagnostics.single().code)
            assertEquals(0, output.size())
        }
    }


    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun golden(name: String, image: java.awt.image.BufferedImage) {
        requireNotNull(javaClass.getResourceAsStream("/checkbox/$name")).use {
            assertImagesMatch(ImageIO.read(it), image, "Checkbox $name") {
                pixelTolerance = 0.0; allowedDifferentPixelsPercent = 0.0; threshold = 1
            }
        }
    }

    private fun provider(): DocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val validator = PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")),
            Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }, temporary)
        return DocxPdfProvider(NativePdfConfiguration(fonts, validator))
    }

    private fun source(letter: Boolean): ByteArray {
        val parts = PdfFixture.unzip(resource("/own-engine/source.docx")).toMutableMap()
        val document = parts.getValue("word/document.xml").decodeToString()
        val section = Regex("<w:sectPr[\\s\\S]*?</w:sectPr>").find(document)!!.value.let {
            if (letter) it.replace(Regex("<w:pgSz[^>]*/>"), "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>") else it
        }
        val examples = listOf(
            "Default: {yes | checkbox()} {no | checkbox()} end",
            "Unboxed: {yes | checkbox(false)} {no | checkbox(false)} end",
            "Cross: {yes | checkbox(true, 'cross', 'empty')} {no | checkbox(true, 'check', 'cross')} end",
            "Comparison: {(gender == 'Vrouw') | checkbox()} {missing ?? false | checkbox()} end",
        ) + listOf(true, false).flatMap { box -> listOf("check", "cross", "empty").map { mark ->
            "$box $mark: {yes | checkbox($box, '$mark', '$mark')} {no | checkbox($box, '$mark', '$mark')} end"
        } }
        val body = examples.mapIndexed { index, text ->
            """<w:p><w:pPr><w:spacing w:line="400" w:lineRule="exact"/></w:pPr><w:r><w:rPr>
                <w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="${22 + index % 4 * 2}"/>
                <w:color w:val="127654"/></w:rPr><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""
        }.joinToString("") + weightComparison() + """<w:p><w:pPr><w:spacing w:line="240" w:lineRule="auto"/></w:pPr>
            <w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/>
            <w:color w:val="127654"/></w:rPr><w:t>{yes | checkbox()}</w:t></w:r></w:p>"""
        parts["word/document.xml"] = document.replace(Regex("<w:body>[\\s\\S]*</w:body>"), "<w:body>$body$section</w:body>").toByteArray()
        return PdfFixture.zip(parts)
    }

    private fun weightComparison(): String = listOf("boxed" to "true, 'check', 'cross'",
        "bare" to "false, 'check', 'cross'", "empty" to "true, 'empty', 'empty'").joinToString("") { (label, args) ->
        """<w:p><w:pPr><w:spacing w:line="400" w:lineRule="exact"/></w:pPr>""" +
            listOf(false, true).joinToString("") { bold ->
                """<w:r><w:rPr><w:b w:val="$bold"/><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>
                    <w:sz w:val="22"/><w:color w:val="127654"/></w:rPr><w:t xml:space="preserve">${if (bold) "Bold" else "Normal"} $label: {yes | checkbox($args)} {no | checkbox($args)}    </w:t></w:r>"""
            } + "</w:p>"
    }

    private fun report(letter: Boolean): ByteArray {
        val parts = PdfFixture.unzip(resource("/own-engine-report/source.docx")).toMutableMap()
        for (name in listOf("word/document.xml", "word/header1.xml", "word/footer1.xml")) {
            var xml = parts.getValue(name).decodeToString()
            xml = if (name == "word/document.xml") xml.replace(Regex("W[0-9]{2}(?=</w:t>)")) {
                "${it.value} {yes | checkbox()}"
            } else xml.replaceFirst("</w:t>", " {no | checkbox(true, 'check', 'cross')}</w:t>")
            if (letter) xml = xml.replace(Regex("<w:pgSz[^>]*/>"), "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>")
            parts[name] = xml.toByteArray()
        }
        return PdfFixture.zip(parts)
    }

    @Test
    fun `checkboxes in repeated table rows and page bands retain text and drawing placement across pages`() {
        val directory = Files.createDirectories(Path.of("build/149-checkbox/report"))
        val data = mapOf("yes" to true, "no" to false)
        val provider = provider()
        for (letter in listOf(false, true)) {
            val label = if (letter) "letter" else "a4"
            val source = report(letter)
            Files.write(directory.resolve("$label-template.docx"), source)
            val docx = ByteArrayOutputStream()
            DocxProvider().generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.DOCX)), data), docx, GenerationContext())
            Files.write(directory.resolve("$label-actual.docx"), docx.toByteArray())
            val output = ByteArrayOutputStream()
            try {
                provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF)), data), output, GenerationContext())
            } catch (error: GenerationException) {
                throw AssertionError(error.diagnostics.joinToString("\n") { "${it.code} ${it.sourceContext?.explanation} ${it.location}" }, error)
            }
            Files.write(directory.resolve("$label.pdf"), output.toByteArray())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertTrue(pdf.numberOfPages >= 3)
                val text = PDFTextStripper().getText(pdf)
                for (n in 1..28) assertEquals(1, Regex("W${n.toString().padStart(2, '0')}").findAll(text).count())
                assertFalse(text.contains("checkbox"))
                for (p in 0 until pdf.numberOfPages) {
                    val image = PDFRenderer(pdf).renderImageWithDPI(p, 96f)
                    ImageIO.write(image, "png", directory.resolve("$label-page-${p + 1}.png").toFile())
                    golden("report-$label/page-${p + 1}.png", image)
                }
            }
        }
    }

    @Test
    fun `checkbox produces qualified opaque vectors in A4 and Letter across all PDF profiles`() {
        val provider = provider()
        val data = mapOf("yes" to true, "no" to false, "gender" to "Vrouw")
        val directory = Files.createDirectories(Path.of("build/149-checkbox"))
        Files.write(directory.resolve("template.docx"), source(false))
        val docx = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(source(false).inputStream(), GenerationRequirements(
            FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX)), data), docx, GenerationContext())
        Files.write(directory.resolve("actual.docx"), docx.toByteArray())
        for (letter in listOf(false, true)) for (profile in PdfOutputProfile.entries) {
            val output = ByteArrayOutputStream()
            try {
                provider.generate(GenerationRequest(source(letter).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                    FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile.id))), data), output, GenerationContext())
            } catch (error: GenerationException) {
                throw AssertionError(error.diagnostics.joinToString("\n") { "${it.code} ${it.message} ${it.sourceContext?.explanation} ${it.location}" }, error)
            }
            Files.write(directory.resolve("${if (letter) "letter" else "a4"}-${profile.id}.pdf"), output.toByteArray())
            Loader.loadPDF(output.toByteArray()).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                assertEquals(if (letter) 612f else 595.3f, pdf.getPage(0).mediaBox.width, .1f)
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains("Default:") && text.contains("Comparison:"))
                assertFalse(text.contains("checkbox"))
                val parser = PDFStreamParser(pdf.getPage(0))
                val commands = parser.parse().filterIsInstance<Operator>().map { it.name }
                assertTrue(commands.count { it == "S" } >= 10, "Checkbox paths must be strokes")
                assertTrue(pdf.getPage(0).resources.fontNames.all { pdf.getPage(0).resources.getFont(it).isEmbedded })
                if (!letter && profile == PdfOutputProfile.STANDARD) {
                    val image = PDFRenderer(pdf).renderImageWithDPI(0, 96f)
                    ImageIO.write(image, "png", directory.resolve("page-1.png").toFile())
                    golden("variants/page-1.png", image)
                }
            }
        }
        assertEquals(0, Files.list(temporary).use { it.count() })
    }
}
