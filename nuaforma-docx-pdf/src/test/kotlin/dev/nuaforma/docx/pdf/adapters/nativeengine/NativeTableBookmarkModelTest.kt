package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.PdfFixture
import dev.nuaforma.pdf.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeTableBookmarkModelTest {

    private fun source(name: String = "source.docx") = requireNotNull(javaClass.getResourceAsStream("/table-bookmark-ends/a4/$name")).use { it.readBytes() }

    private fun read(bytes: ByteArray) = NativeDocxReader(GenerationContext(), { _, _, _ -> "fixture" }).read(bytes)

    private fun paragraphs(blocks: List<PdfBlock>): List<PdfParagraph> = blocks.flatMap { block -> when (block) {
        is PdfParagraph -> listOf(block)
        is PdfTable -> block.rows.flatMap { row -> row.cells.flatMap { paragraphs(it.blocks) } }
        else -> emptyList()
    } }

    private fun text(model: PdfDocument) = paragraphs(model.blocks).map { p -> p.runs.filterIsInstance<PdfTextRun>().joinToString("") { it.text } }

    private fun changed(transform: (String) -> String): ByteArray {
        val parts = PdfFixture.unzip(source())
        val before = parts.getValue("word/document.xml").decodeToString()
        val after = transform(before)
        require(before != after)
        return PdfFixture.zip(parts.mapValues { (name, bytes) -> if (name == "word/document.xml") after.toByteArray() else bytes })
    }

    @Test
    fun `table endings preserve cells text navigation and source without adding paragraphs`() {
        val input = source()
        val before = input.copyOf()
        val model = read(input)
        assertEquals(text(read(source("control-source.docx"))), text(model))
        val table = model.blocks.filterIsInstance<PdfTable>().single()
        assertEquals(listOf(130f, 180f), table.columnWidths)
        assertEquals(2, table.rows.size)
        assertEquals(listOf(2, 2), table.rows.map { it.cells.size })
        val destinations = paragraphs(model.blocks).flatMap { p -> p.runs.filterIsInstance<PdfTextRun>().flatMap { it.navigation?.destinations.orEmpty() } }
        assertEquals(listOf("_BeforeTable", "_FirstRow", "_LastRow"), destinations.map { it.name })
        assertArrayEquals(before, input)
    }

    @Test
    fun `table markers must close already open IDs in actual document order`() {
        val invalid = listOf<(String) -> String>(
            { it.replace("<w:bookmarkEnd w:id=\"21\"/>", "<w:bookmarkEnd w:id=\"TEMP\"/>")
                .replace("<w:bookmarkEnd w:id=\"22\"/>", "<w:bookmarkEnd w:id=\"21\"/>")
                .replace("<w:bookmarkEnd w:id=\"TEMP\"/>", "<w:bookmarkEnd w:id=\"22\"/>") },
            { it.replace("<w:bookmarkEnd w:id=\"23\"/>", "") },
            { it.replace("<w:bookmarkEnd w:id=\"22\"/>", "<w:bookmarkEnd w:id=\"21\"/>") },
            { it.replace("<w:bookmarkEnd w:id=\"21\"/>", "<w:bookmarkEnd w:id=\"21\" extra=\"1\"/>") })
        for (transform in invalid) assertThrows(NativeDocxReadException::class.java) { read(changed(transform)) }
    }

    @Test
    fun `affected text references remain unsupported while unrelated paragraph references work`() {
        val reference = """<w:p><w:fldSimple w:instr="REF _FirstRow"><w:r><w:t>Stale cache</w:t></w:r></w:fldSimple></w:p>"""
        val error = assertThrows(NativeDocxReadException::class.java) { read(changed { it.replace("<w:sectPr", reference + "<w:sectPr") }) }
        assertTrue(error.message!!.contains("Text references to block-level bookmark ends"))
        val safe = """<w:p><w:bookmarkStart w:id="99" w:name="Safe"/><w:r><w:t>SAFE_BOOKMARK</w:t></w:r><w:bookmarkEnd w:id="99"/></w:p>"""
        val safeReference = reference.replace("_FirstRow", "Safe")
        val model = read(changed { it.replace("<w:body>", "<w:body>$safe").replace("<w:sectPr", safeReference + "<w:sectPr") })
        assertEquals(listOf("Safe"), model.referenceTargets.map { it.name })
    }
}
