package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path

class NativeViewSettingsTest {
    @TempDir lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `qualified view indicators and read-only recommendations preserve existing Word control pixels`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("background", "form-indicator", "recommended", "disabled")) {
            support.verify("/view-settings/$variant/$paper/", if (variant == "background" && paper == "a4")
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard"),
                Regex("(?!)"), 0, golden = "/preferred-table-widths/$paper/page-1.png")
        }
    }

    @Test
    fun `invalid read-only and view settings retain original source diagnostics`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/view-settings/recommended/a4/source.docx"
        for (replacement in listOf(
            "<w:writeProtection w:recommended=\"yes\"/>",
            "<w:writeProtection recommended=\"1\"/>",
            "<w:writeProtection w:recommended=\"1\" w:cryptAlgorithmClass=\"hash\"/>",
            "<w:writeProtection w:recommended=\"1\"><w:unknown/></w:writeProtection>",
            "<w:writeProtection w:recommended=\"1\"/><w:writeProtection w:recommended=\"0\"/>")) {
            support.rejectChanged(source, "word/settings.xml", "writeProtection") {
                it.replace("<w:writeProtection w:recommended=\"1\"/>", replacement)
            }
        }
        for (name in listOf("displayBackgroundShape", "doNotShadeFormData")) {
            val variant = if (name == "displayBackgroundShape") "background" else "form-indicator"
            for (replacement in listOf("<w:$name w:val=\"yes\"/>", "<w:$name foreign=\"1\"/>", "<w:$name/><w:$name/>")) {
                support.rejectChanged("/view-settings/$variant/a4/source.docx", "word/settings.xml", name) {
                    it.replace("<w:$name/>", replacement)
                }
            }
        }
    }

    @Test
    fun `view flags never suppress an independently unsupported visible document background`() {
        NativeWordFeatureSupport(temporary).rejectChanged("/view-settings/background/a4/source.docx",
            "word/document.xml", "background") {
            it.replace("<w:body>", "<w:background w:color=\"112233\"/><w:body>")
        }
    }

    @Test
    fun `template cleanup preserves supported view settings and ordinary DOCX output retains them`() {
        val support = NativeWordFeatureSupport(temporary)
        for (variant in listOf("background", "form-indicator", "recommended", "disabled")) {
            val source = support.bytes("/view-settings/$variant/a4/source.docx")
            val cleaned = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), cleaned, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))).changes.isEmpty())
            assertArrayEquals(source, cleaned.toByteArray())
            val output = ByteArrayOutputStream()
            support.provider.generate(GenerationRequest(source.inputStream(), GenerationRequirements(FormatRequest(DocumentFormat.DOCX),
                FormatRequest(DocumentFormat.DOCX))), output, GenerationContext())
            val settings = PdfFixture.unzip(output.toByteArray()).getValue("word/settings.xml").decodeToString()
            val expected = when (variant) {
                "recommended" -> "writeProtection"
                "form-indicator" -> "doNotShadeFormData"
                else -> "displayBackgroundShape"
            }
            assertTrue(settings.contains(expected))
        }
    }

}
