package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.pdf.PdfProcessValidator
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class PassivePackagePdfTest {
    @TempDir lateinit var temporary: Path
    private val office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }

    private fun provider(): NativeDocxPdfProvider {
        val fonts = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic", "boldItalic" to "BoldItalic").map { (face, file) ->
            NativePdfFont("Liberation Sans", face, resource("/own-engine-fonts/LiberationSans-$file.ttf"))
        }
        val jars = Files.list(Path.of(System.getProperty("nuaforma.pdf.validation"))).use { paths -> paths.filter { it.toString().endsWith(".jar") }.sorted().toList() }
        return NativeDocxPdfProvider(NativePdfConfiguration(fonts, PdfProcessValidator(Path.of(System.getProperty("nuaforma.pdf.validation.java")), jars, temporary)))
    }

    private fun parts(fixture: String = "/own-engine/source.docx"): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { parts ->
        ZipInputStream(resource(fixture).inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                parts[entry.name] = if (entry.name.endsWith(".png")) {
                    val source = ImageIO.read(bytes.inputStream())
                    val opaque = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
                    val graphics = opaque.createGraphics()
                    try { graphics.color = Color.WHITE; graphics.fillRect(0, 0, source.width, source.height); graphics.drawImage(source, 0, 0, null) }
                    finally { graphics.dispose() }
                    ByteArrayOutputStream().also { ImageIO.write(opaque, "png", it) }.toByteArray()
                } else bytes
            }
        }
    }

    private fun pack(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> parts.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() } }
    }.toByteArray()

    private fun add(parts: MutableMap<String, ByteArray>, part: String, before: String, value: String) {
        parts[part] = parts.getValue(part).decodeToString().replace(before, value + before).toByteArray()
    }

    private fun metadata(parts: MutableMap<String, ByteArray>) {
        parts["docProps/custom.xml"] = """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="2" name="{notATemplate"><vt:lpwstr>{unclosed العربية</vt:lpwstr></property></Properties>""".toByteArray()
        add(parts, "_rels/.rels", "</Relationships>", """<Relationship Id="custom182" Type="$office/custom-properties" Target="docProps/custom.xml"/>""")
        add(parts, "[Content_Types].xml", "</Types>", """<Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/>""")
        val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        if ("word/settings.xml" !in parts) {
            parts["word/settings.xml"] = """<w:settings xmlns:w="$word"></w:settings>""".toByteArray()
            add(parts, "word/_rels/document.xml.rels", "</Relationships>", """<Relationship Id="settings182" Type="$office/settings" Target="settings.xml"/>""")
            add(parts, "[Content_Types].xml", "</Types>", """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""")
        }
        parts["word/settings.xml"] = parts.getValue("word/settings.xml").decodeToString().replace(Regex("<w:settings([^>]*)/>"), "<w:settings$1></w:settings>").toByteArray()
        add(parts, "word/settings.xml", "</w:settings>", """<w:attachedTemplate xmlns:r="$office" r:id="template182"/>""")
        val switches = listOf("overrideTableStyleFontSizeAndJustification", "enableOpenTypeFeatures", "doNotFlipMirrorIndents", "differentiateMultirowTableHeaders")
            .joinToString("") { """<w:compatSetting w:name="$it" w:uri="http://schemas.microsoft.com/office/word" w:val="1"/>""" }
        add(parts, "word/settings.xml", "</w:settings>", """<w:embedSystemFonts/><w:stylePaneFormatFilter w:val="3F01"/><w:documentProtection w:edit="forms" w:enforcement="1"/>
            <w:noPunctuationKerning/><w:doNotIncludeSubdocsInStats/><w15:chartTrackingRefBased xmlns:w15="http://schemas.microsoft.com/office/word/2012/wordml"/>
            <w:hdrShapeDefaults/><w:compat><w:doNotUseHTMLParagraphAutoSpacing w:val="0"/>$switches</w:compat>""")
        parts["word/_rels/settings.xml.rels"] = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="template182" Type="$office/attachedTemplate" Target="https://missing.invalid/no-network.dotx" TargetMode="External"/></Relationships>""".toByteArray()
        parts["word/webSettings.xml"] = """<w:webSettings xmlns:w="$word" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" mc:Ignorable="w"><w:divs><w:div w:id="1"><w:bodyDiv/><w:marTop w:val="720"/><w:divBdr><w:top w:val="single"/></w:divBdr></w:div></w:divs><w:optimizeForBrowser/></w:webSettings>""".toByteArray()
        add(parts, "word/_rels/document.xml.rels", "</Relationships>", """<Relationship Id="web182" Type="$office/webSettings" Target="webSettings.xml"/>""")
        add(parts, "[Content_Types].xml", "</Types>", """<Override PartName="/word/webSettings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.webSettings+xml"/>""")
        add(parts, "word/document.xml", "</w:sectPr>", """<w:docGrid w:linePitch="360"/>""")
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString().replace("<w:pgSz ", "<w:pgSz w:code=\"9\" ").toByteArray()
    }

    private fun render(provider: NativeDocxPdfProvider, source: ByteArray, profile: String): ByteArray = ByteArrayOutputStream().also {
        provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
            FormatRequest(DocumentFormat.PDF, mapOf("profile" to profile)))), it, GenerationContext())
    }.toByteArray()

    @Test fun `custom properties and detached templates preserve PDF text pixels and embedding in all profiles`() {
        val provider = provider()
        val original = parts()
        val decorated = parts().also(::metadata)
        val source = pack(decorated)
        val snapshot = source.copyOf()
        for (profile in listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b")) {
            Loader.loadPDF(render(provider, pack(original), profile)).use { expected ->
                Loader.loadPDF(render(provider, source, profile)).use { actual ->
                    assertEquals(expected.numberOfPages, actual.numberOfPages)
                    assertEquals(PDFTextStripper().getText(expected), PDFTextStripper().getText(actual))
                    for (page in 0 until actual.numberOfPages) {
                        assertTrue(actual.getPage(page).resources.fontNames.all { actual.getPage(page).resources.getFont(it).isEmbedded })
                        val a = PDFRenderer(expected).renderImage(page)
                        val b = PDFRenderer(actual).renderImage(page)
                        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
                        assertArrayEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), b.getRGB(0, 0, b.width, b.height, null, 0, b.width), profile)
                    }
                }
            }
        }
        assertArrayEquals(snapshot, source)
    }

    @Test fun `unsupported package member names reach native rejection diagnostics without publishing output`() {
        val document = parts()
        document["word/unsupported182.xml"] = """<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"/>""".toByteArray()
        add(document, "word/_rels/document.xml.rels", "</Relationships>", """<Relationship Id="unsupported182" Type="$office/comments" Target="unsupported182.xml"/>""")
        add(document, "[Content_Types].xml", "</Types>", """<Override PartName="/word/unsupported182.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/>""")
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenerationException::class.java) {
            provider().generate(GenerationRequest(pack(document).inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.PDF, mapOf("profile" to "standard")))), output, GenerationContext())
        }
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, error.diagnostics.first().code)
        assertEquals("word/unsupported182.xml", error.diagnostics.first().part)
        assertEquals(0, output.size())
    }

    @Test fun `custom properties do not qualify dynamic Word property fields`() {
        val document = parts().also(::metadata)
        document["word/document.xml"] = document.getValue("word/document.xml").decodeToString().replace("<w:body>",
            """<w:body><w:p><w:fldSimple w:instr=" DOCPROPERTY Example "><w:r><w:t>Cached</w:t></w:r></w:fldSimple></w:p>""").toByteArray()
        val error = assertThrows(GenerationException::class.java) { render(provider(), pack(document), "standard") }
        assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, error.diagnostics.first().code)
        assertEquals("word/document.xml", error.diagnostics.first().part)
    }

    @Test fun `compatibility permissions do not silently enable unsupported effective formatting`() {
        val provider = provider()
        for (paragraph in listOf(
            """<w:p><w:pPr><w:divId w:val="1"/></w:pPr><w:r><w:t>HTML division binding</w:t></w:r></w:p>""")) {
            val document = parts().also(::metadata)
            document["word/document.xml"] = document.getValue("word/document.xml").decodeToString().replace("<w:body>", "<w:body>$paragraph").toByteArray()
            val error = assertThrows(GenerationException::class.java) { render(provider, pack(document), "standard") }
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, error.diagnostics.first().code)
            assertEquals("word/document.xml", error.diagnostics.first().part)
        }
    }

    @Test fun `list gallery metadata preserves actual numbered PDF text and page pixels`() {
        val provider = provider()
        val original = parts("/own-engine-lists/source.docx")
        val decorated = original.toMutableMap()
        var identifier = 100
        decorated["word/numbering.xml"] = original.getValue("word/numbering.xml").decodeToString()
            .replace("<w:abstractNum ", "<w:abstractNum xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\" w15:restartNumberingAfterBreak=\"0\" ")
            .replace("<w:lvl ", "<w:lvl w:tplc=\"04090001\" w:tentative=\"1\" ")
            .replace(Regex("<w:num ")) { "<w:num xmlns:w16cid=\"http://schemas.microsoft.com/office/word/2016/wordml/cid\" w16cid:durableId=\"${identifier++}\" " }.toByteArray()
        Loader.loadPDF(render(provider, pack(original), "standard")).use { expected ->
            Loader.loadPDF(render(provider, pack(decorated), "standard")).use { actual ->
                assertEquals(expected.numberOfPages, actual.numberOfPages)
                assertEquals(PDFTextStripper().getText(expected), PDFTextStripper().getText(actual))
                for (page in 0 until actual.numberOfPages) {
                    val a = PDFRenderer(expected).renderImage(page)
                    val b = PDFRenderer(actual).renderImage(page)
                    assertEquals(a.width, b.width); assertEquals(a.height, b.height)
                    assertArrayEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), b.getRGB(0, 0, b.width, b.height, null, 0, b.width))
                }
            }
        }
    }

    @Test fun `unprinted footer needs no reserved height but visible content beyond the physical page rejects at section geometry`() {
        val provider = provider()

        fun document(content: String, offset: Int): ByteArray {
            val parts = parts()
            parts["word/footer92.xml"] = """<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:p>$content</w:p></w:ftr>""".toByteArray()
            val reference = """<w:footerReference xmlns:r="$office" w:type="default" r:id="footer182"/>"""
            var main = parts.getValue("word/document.xml").decodeToString().replace(Regex("<w:footerReference\\b[^>]*/>"), "")
                .replace(Regex("<w:sectPr\\b[^>]*>")) { it.value + reference }
            main = if (main.contains("w:footer=")) main.replace(Regex("w:footer=\"[0-9]+\""), "w:footer=\"$offset\"")
                else main.replace("<w:pgMar ", "<w:pgMar w:footer=\"$offset\" ")
            parts["word/document.xml"] = main.toByteArray()
            add(parts, "word/_rels/document.xml.rels", "</Relationships>", """<Relationship Id="footer182" Type="$office/footer" Target="footer92.xml"/>""")
            add(parts, "[Content_Types].xml", "</Types>", """<Override PartName="/word/footer92.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""")
            return pack(parts)
        }

        Loader.loadPDF(render(provider, document("", 0), "standard")).use { expected ->
            Loader.loadPDF(render(provider, document("", 20_000), "standard")).use { actual ->
                assertEquals(expected.numberOfPages, actual.numberOfPages)
                assertEquals(PDFTextStripper().getText(expected).lineSequence().filter { it.isNotBlank() }.toList(),
                    PDFTextStripper().getText(actual).lineSequence().filter { it.isNotBlank() }.toList())
                for (page in 0 until actual.numberOfPages) {
                    val a = PDFRenderer(expected).renderImage(page)
                    val b = PDFRenderer(actual).renderImage(page)
                    assertEquals(a.width, b.width); assertEquals(a.height, b.height)
                    assertArrayEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width), b.getRGB(0, 0, b.width, b.height, null, 0, b.width))
                }
            }
        }
        for (content in listOf("<w:r><w:t>Visible</w:t></w:r>", "<w:r><w:rPr><w:u w:val=\"single\"/></w:rPr><w:t xml:space=\"preserve\"> </w:t></w:r>",
            "<w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\" w:color=\"000000\"/></w:pBdr></w:pPr>",
            "<w:fldSimple w:instr=\" PAGE \"><w:r><w:t>1</w:t></w:r></w:fldSimple>")) {
            val error = assertThrows(GenerationException::class.java) { render(provider, document(content, 20_000), "standard") }
            assertEquals(DiagnosticCodes.UNSUPPORTED_INPUT, error.diagnostics.first().code)
            assertEquals("word/document.xml", error.diagnostics.first().part)
        }
    }

}
