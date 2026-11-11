package dev.nuaforma.docx.pdf

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.adapters.nativeengine.NativeDocxReader
import dev.nuaforma.pdf.PdfParagraph
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.Executors

class NativeNumberingStylesTest {
    @TempDir lateinit var temporary: Path

    private val levels = linkedMapOf(
        "control0" to 0, "control1" to 1, "control2" to 2, "definition-link" to 0,
        "inherited-unlinked-ilvl1" to 1, "associated1-agreed" to 1, "associated1-mismatch" to 0,
        "direct0-associated1" to 0, "direct2-associated1" to 2, "base1-derived2" to 2,
        "base1-derived-absent" to 1, "base1-derived-id-only" to 1, "base1-derived-level-only" to 2,
        "direct-id-only-style1" to 1, "direct-level-only-style1" to 2, "associated1-style-id-only" to 0,
        "associated1-direct-id-style0" to 0, "associated-no-style-numbering-direct-id" to 0,
        "associated-no-numbering-anywhere" to null, "associated-style-level-only-direct-id" to 1,
        "associated-style-id-only-direct-level" to 2, "associated-style-level1-direct-disable" to null,
        "associated-style-level1-direct-instance2" to 1, "custom-outline-absent" to 0,
        "custom-outline-zero" to 0, "custom-outline-one" to 1, "heading-absent" to 0,
        "heading-zero" to 0, "heading-one" to 1, "heading-direct-zero" to 0,
        "missing-style" to 0, "non-numbering-style" to 0, "inherited-without-effects" to 1,
    )

    @ParameterizedTest
    @ValueSource(strings = ["a4", "letter"])
    fun `definition links inheritance direct overrides and outline styles match actual Word`(paper: String) {
        val support = NativeWordFeatureSupport(temporary)
        for ((kind, level) in levels) {
            val control = if (level == null) "associated-no-numbering-anywhere" else "control$level"
            val profiles = if (paper == "a4" && kind in setOf("definition-link", "base1-derived2", "inherited-without-effects"))
                listOf("standard", "pdfa-1b", "pdfa-2b", "pdfa-3b") else listOf("standard")
            support.verify("/word-numbering-style/$kind/$paper/", profiles, Regex("N[0-9]{2}"), 3,
                golden = "/word-numbering-style/$control/$paper/page-1.png")
        }
    }

    @Test
    fun `numbering levels and counters are request local and unused definition fonts are inactive`() {
        val support = NativeWordFeatureSupport(temporary)
        val sources = levels.keys.associateWith { support.bytes("/word-numbering-style/$it/a4/source.docx") }
        val originals = sources.mapValues { it.value.copyOf() }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 2).flatMap { levels.entries.toList() }.map { (kind, expected) ->
                executor.submit {
                    val model = NativeDocxReader(GenerationContext(), { family, _, _ ->
                        assertEquals("Liberation Sans", family)
                        "fixture"
                    }).read(sources.getValue(kind))
                    val labels = model.blocks.filterIsInstance<PdfParagraph>().mapNotNull { it.listLabel?.value }
                    if (expected == null) assertTrue(labels.isEmpty(), kind) else {
                        assertEquals(listOf(expected, expected, expected), labels.map { it.level }, kind)
                        assertEquals(listOf("1.", "2.", "3."), labels.map { it.text }, kind)
                    }
                }
            }
            tasks.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        for ((kind, bytes) in sources) assertArrayEquals(originals.getValue(kind), bytes, kind)
    }

    @Test
    fun `inert names do not activate templates or disappear from cleanup output`() {
        val support = NativeWordFeatureSupport(temporary)
        val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
        for (kind in listOf("definition-link", "missing-style", "non-numbering-style", "inherited-without-effects")) {
            val source = support.bytes("/word-numbering-style/$kind/a4/source.docx")
            val output = ByteArrayOutputStream()
            assertTrue(support.provider.cleanTemplate(source.inputStream(), output, context).changes.isEmpty(), kind)
            val original = PdfFixture.unzip(source)
            val cleaned = PdfFixture.unzip(output.toByteArray())
            assertEquals(original.keys, cleaned.keys)
            original.forEach { (part, bytes) -> assertArrayEquals(bytes, cleaned[part], "$kind/$part") }
            assertTrue(support.generate(output.toByteArray(), "standard").isNotEmpty())
        }
    }

    @Test
    fun `malformed links and active redirection fail with original numbering locations`() {
        val support = NativeWordFeatureSupport(temporary)
        val source = "/word-numbering-style/definition-link/a4/source.docx"
        val original = PdfFixture.unzip(support.bytes(source)).getValue("word/numbering.xml").decodeToString()
        val link = Regex("<w:styleLink[^>]*/>").find(original)!!.value
        for ((replacement, element) in listOf(
            "<w:styleLink/>" to "styleLink",
            "<w:styleLink w:val=\"\"/>" to "styleLink",
            "$link$link" to "styleLink",
            "<w:styleLink w:val=\"List\" unexpected=\"1\"/>" to "styleLink",
            "<w:styleLink w:val=\"List\"><w:t>{hidden</w:t></w:styleLink>" to "styleLink",
            "<w:numStyleLink w:val=\"List\"/>" to "numStyleLink")) {
            support.rejectChanged(source, "word/numbering.xml", element) { it.replace(link, replacement) }
        }
        support.rejectChanged("/word-numbering-style/inherited-without-effects/a4/source.docx", "word/styles.xml", "ilvl") {
            it.replaceFirst("<w:ilvl w:val=\"1\"/>", "<w:ilvl w:val=\"9\"/>")
        }
    }
}
