package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.*
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import dev.nuaforma.pdf.PdfBlock
import dev.nuaforma.pdf.PdfParagraph
import dev.nuaforma.pdf.PdfTable
import dev.nuaforma.pdf.PdfTextRun
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.xml.namespace.QName

class WordReviewAnnotationsTest {
    private val context = GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT))
    private val xml = ConversionXml(context)
    private val word = WordReviewMetadata.word

    private fun bytes(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }

    private fun roots(variant: String = "modern"): MutableMap<String, XmlElement> =
        PdfFixture.unzip(bytes("/review-annotations/$variant/a4/source.docx"))
            .filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }
            .mapValues { (part, bytes) -> xml.readPart(bytes, part) }.toMutableMap()

    private fun normalize(parts: MutableMap<String, XmlElement>): WordReviewAnnotations.Change {
        val source = NativeDocxSource(context)
        parts.forEach { (part, root) -> source.index(root, part) }
        return WordReviewAnnotations(context) { part, node, message -> source.current = node; source.failure(message, part) }.normalize(parts)
    }

    private fun rejected(part: String, transform: (String) -> String): NativeDocxReadException {
        val parts = roots()
        val before = xml.write(parts.getValue(part)).decodeToString()
        val changed = transform(before)
        assertNotEquals(before, changed)
        parts[part] = xml.readPart(changed.toByteArray(), part)
        val snapshot = parts.mapValues { xml.write(it.value) }
        val failure = assertThrows(NativeDocxReadException::class.java) { normalize(parts) }
        assertEquals(snapshot.keys, parts.keys)
        snapshot.forEach { (name, expected) -> assertArrayEquals(expected, xml.write(parts.getValue(name)), name) }
        return failure
    }

    private fun texts(blocks: List<PdfBlock>): List<String> = blocks.flatMap { block -> when (block) {
        is PdfParagraph -> listOf(block.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text })
        is PdfTable -> block.rows.flatMap { row -> row.cells.flatMap { texts(it.blocks) } }
        else -> emptyList()
    } }

    @Test
    fun `direct structural reading preserves body content and never resolves comment-only fonts`() {
        val control = NativeDocxReader(context, { _, _, _ -> "fixture" }).read(bytes("/preferred-table-widths/a4/source.docx"))
        for (variant in listOf("classic", "modern", "stale-modern", "reference-style")) {
            val input = bytes("/review-annotations/$variant/a4/source.docx")
            val before = input.copyOf()
            val families = mutableSetOf<String>()
            val actual = NativeDocxReader(context, { family, _, _ -> families += family; "fixture" }).read(input)
            assertEquals(control.page, actual.page)
            assertEquals(texts(control.blocks), texts(actual.blocks))
            assertTrue(families.none { it.startsWith("Missing") }, families.toString())
            assertArrayEquals(before, input)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["classic", "modern", "stale-modern", "reference-style"])
    fun `review graph pruning preserves every unrelated part and removes reference-only run styles`(variant: String) {
        val parts = roots(variant)
        val unaffected = parts.filterKeys { it !in setOf("word/document.xml", "word/_rels/document.xml.rels", "[Content_Types].xml") &&
            WordReviewMetadata.definitions.none { definition -> definition.path == it } }.mapValues { xml.write(it.value) }
        val change = normalize(parts)
        assertEquals(if (variant == "classic") 1 else 5, change.removed.size)
        assertTrue(change.removed.none { it in parts })
        assertEquals(if (variant == "stale-modern") 2 else 3, change.edited.size)
        assertTrue(parts.getValue("word/document.xml").descendants().none { it.name.localPart.startsWith("comment") })
        assertTrue(parts.getValue("word/document.xml").descendants().none { it.attribute(word, "val") == "ReviewReference" })
        unaffected.forEach { (name, expected) -> assertArrayEquals(expected, xml.write(parts.getValue(name)), name) }
    }

    @Test
    fun `stale cache identifiers need not resolve to deleted comments and contacts remain inert`() {
        val parts = roots("stale-modern")
        val people = parts.getValue("word/people.xml")
        people.descendants().single { it.name.localPart == "presenceInfo" }.attributes[QName(WordReviewMetadata.w15, "userId")] = "https://example.invalid/local-identity"
        assertTrue(WordReviewMetadata(context) { part, _, message -> NativeDocxReadException(message, part) }.validate(parts).isEmpty())
        assertEquals(5, normalize(parts).removed.size)
    }

    @Test
    fun `point anchors ranges duplicates and cross-story references retain their actual source`() {
        for ((old, new) in listOf("<w:commentRangeEnd w:id=\"0\"></w:commentRangeEnd>" to "",
            "<w:commentReference w:id=\"0\"></w:commentReference>" to "<w:commentReference w:id=\"0\"/><w:commentReference w:id=\"0\"/>",
            "w:id=\"0\"" to "w:id=\"999\"")) {
            val failure = rejected("word/document.xml") { it.replaceFirst(old, new) }
            assertEquals("word/document.xml", failure.part)
            assertTrue(failure.sourceContext.path.orEmpty().contains("comment"))
        }
        val parts = roots()
        parts["word/header1.xml"] = xml.read("""<w:hdr xmlns:w="$word"><w:p><w:r><w:commentReference w:id="0"/></w:r></w:p></w:hdr>""".toByteArray())
        assertTrue(assertThrows(NativeDocxReadException::class.java) { normalize(parts) }.message.orEmpty().contains("cross-story"))
    }

    @Test
    fun `reversed ranges fail before any graph mutation`() {
        val failure = rejected("word/document.xml") {
            it.replace("commentRangeStart", "commentTemporary").replace("commentRangeEnd", "commentRangeStart").replace("commentTemporary", "commentRangeEnd")
        }
        assertTrue(failure.message.orEmpty().contains("Reversed"))
    }

    @Test
    fun `unknown comment payload is located at the offending field instead of its paragraph`() {
        val failure = rejected("word/comments.xml") {
            it.replaceFirst("<w:p w14:paraId=\"10000001\">", "<w:p w14:paraId=\"10000001\"><w:fldSimple w:instr=\"INCLUDETEXT ignored\"/>")
        }
        assertEquals("word/comments.xml", failure.part)
        assertTrue(failure.sourceContext.path.orEmpty().contains("fldSimple"))
    }

    @Test
    fun `malformed review identities timestamps toggles and namespaces fail in their own parts`() {
        val cases = listOf(
            Triple("word/commentsIds.xml", "20000002", "20000001"),
            Triple("word/commentsIds.xml", "20000001", "not-hex"),
            Triple("word/commentsExtensible.xml", "2026-11-09T12:00:00Z", "2026-11-09T12:00:00+01:00"),
            Triple("word/commentsExtensible.xml", "2026-11-09T12:00:00Z", "2026-13-09T12:00:00Z"),
            Triple("word/commentsExtended.xml", "w15:done=\"0\"", "w15:done=\"yes\""),
            Triple("word/people.xml", "w15:author=\"Fixture Reviewer\"", "author=\"Fixture Reviewer\""))
        for ((part, old, new) in cases) assertEquals(part, rejected(part) { it.replaceFirst(old, new) }.part)
    }

    @Test
    fun `external links wrong types and duplicate declarations never become silently repaired sources`() {
        val relationshipPart = "word/_rels/document.xml.rels"
        assertEquals(relationshipPart, rejected(relationshipPart) {
            it.replaceFirst("Target=\"comments.xml\"", "Target=\"https://example.invalid/comments.xml\" TargetMode=\"External\"")
        }.part)
        assertEquals("[Content_Types].xml", rejected("[Content_Types].xml") {
            it.replaceFirst("wordprocessingml.comments+xml", "wordprocessingml.document.main+xml")
        }.part)
        assertEquals("word/comments.xml", rejected("word/comments.xml") {
            it.replaceFirst("w:id=\"1\"", "w:id=\"0\"")
        }.part)
    }

    @Test
    fun `empty review relationship parts and their explicit overrides are pruned together`() {
        val parts = roots()
        val relationshipPart = "word/_rels/comments.xml.rels"
        parts[relationshipPart] = xml.read("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>""".toByteArray())
        val types = parts.getValue("[Content_Types].xml")
        types.children += xml.read("""<Override xmlns="http://schemas.openxmlformats.org/package/2006/content-types" PartName="/$relationshipPart" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""".toByteArray())
        assertTrue(relationshipPart in normalize(parts).removed)
        assertFalse(relationshipPart in parts)
        assertTrue(types.elements().none { it.attribute("", "PartName") == "/$relationshipPart" })
    }

    @Test
    fun `review pass does not interpret Word-like XML in unrelated non-printing data`() {
        val parts = roots()
        val unrelated = xml.read("""<data xmlns:w="$word"><w:commentRangeStart w:id="999"/></data>""".toByteArray())
        parts["customXml/item1.xml"] = unrelated
        val before = xml.write(unrelated)
        normalize(parts)
        assertArrayEquals(before, xml.write(parts.getValue("customXml/item1.xml")))
    }

    @Test
    fun `review budgets and cancellation remain enforced without exposing comment contents`() {
        val root = xml.read(("""<w:comments xmlns:w="$word">""" + (0..1_000).joinToString("") {
            """<w:comment w:id="$it" w:author="Fixture"><w:p><w:r><w:t>text</w:t></w:r></w:p></w:comment>"""
        } + "</w:comments>").toByteArray())
        val parts = mapOf("word/comments.xml" to root)
        assertTrue(assertThrows(NativeDocxReadException::class.java) {
            WordReviewMetadata(context) { part, _, message -> NativeDocxReadException(message, part) }.validate(parts)
        }.message.orEmpty().contains("budget"))
        val cancelled = GenerationContext(cancellation = CancellationToken { true })
        val failure = assertThrows(GenerationException::class.java) {
            WordReviewMetadata(cancelled) { part, _, message -> NativeDocxReadException(message, part) }.validate(parts)
        }
        assertEquals(DiagnosticCodes.CANCELLED, failure.diagnostics.single().code)
        val now = Instant.parse("2026-11-09T12:00:00Z")
        val expired = GenerationContext(deadline = now, clock = Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(DiagnosticCodes.RESOURCE_LIMIT_EXCEEDED, assertThrows(GenerationException::class.java) {
            WordReviewMetadata(expired) { part, _, message -> NativeDocxReadException(message, part) }.validate(parts)
        }.diagnostics.single().code)
    }

    @Test
    fun `oversized non-printing text is rejected before graph mutation`() {
        val parts = roots("classic")
        val text = parts.getValue("word/comments.xml").descendants().first { it.name == QName(word, "t") }
        text.children.clear()
        text.children += dev.nuaforma.docx.pdf.adapters.ooxml.XmlText("x".repeat(1_048_577))
        val before = parts.keys.toSet()
        val failure = assertThrows(NativeDocxReadException::class.java) { normalize(parts) }
        assertTrue(failure.message.orEmpty().contains("budget"))
        assertEquals(before, parts.keys)
    }

}
