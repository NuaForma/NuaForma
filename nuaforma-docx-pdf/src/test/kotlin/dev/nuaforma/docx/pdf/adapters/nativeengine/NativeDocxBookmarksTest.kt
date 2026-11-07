package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NativeDocxBookmarksTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val start = """<w:bookmarkStart w:id="1" w:name="Target"/>"""
    private val end = """<w:bookmarkEnd w:id="1"/>"""
    private val text = "<w:r><w:t>Target text</w:t></w:r>"

    private fun normalize(body: String): XmlElement {
        val context = GenerationContext()
        val root = ConversionXml(context).read("""<w:body xmlns:w="$word">$body</w:body>""".toByteArray())
        NativeDocxBookmarks(context).normalize(root, {}, { throw IllegalArgumentException(it) })
        return root
    }

    @Test
    fun `body boundaries preserve paragraph properties text order and the existing paragraph count`() {
        val root = normalize("$start<w:p><w:pPr/>$text</w:p>$end<w:sectPr/>")
        assertEquals(listOf("p", "sectPr"), root.elements().map { it.name.localPart })
        assertEquals(listOf("pPr", "bookmarkStart", "r", "bookmarkEnd"), root.elements().first().elements().map { it.name.localPart })
        val inlineStart = normalize("<w:p>$start$text</w:p>$end<w:sectPr/>")
        assertEquals(listOf("bookmarkStart", "r", "bookmarkEnd"), inlineStart.elements().first().elements().map { it.name.localPart })
    }

    @Test
    fun `table boundary ends and collapsed boundary pairs retain their order without empty paragraphs`() {
        val root = normalize("$start<w:tbl><w:tr><w:tc><w:p>$text</w:p></w:tc></w:tr></w:tbl>$end<w:sectPr/>")
        val paragraph = root.descendants().single { it.name.localPart == "p" }
        assertEquals(listOf("bookmarkStart", "r", "bookmarkEnd"), paragraph.elements().map { it.name.localPart })
        val collapsed = normalize("<w:p>$text</w:p>$start$end<w:p>$text</w:p><w:sectPr/>")
        assertEquals(2, collapsed.elements().count { it.name.localPart == "p" })
        assertEquals(listOf("bookmarkStart", "bookmarkEnd", "r"), collapsed.elements()[1].elements().map { it.name.localPart })
    }

    @Test
    fun `malformed unmatched duplicate and misplaced body boundaries fail at a concrete node`() {
        for (body in listOf("$end<w:p>$text</w:p>", "$start<w:p>$text</w:p>",
            "$start$start<w:p>$text</w:p>$end", "$start<w:p>$text</w:p>$end$end",
            "$start<w:p>$text</w:p>${end.replace("1", "-1")}",
            "${start.replace("/>", " extra=\"1\"/>")}<w:p>$text</w:p>$end",
            "$start<w:p>$text</w:p>${end.replace("/>", ">text</w:bookmarkEnd>")}",
            "<w:p>$start$text</w:p><w:sectPr/>$end",
            "<w:p>$text</w:p>$start$end<w:sectPr/>")) {
            assertThrows(IllegalArgumentException::class.java, { normalize(body) }, body)
        }
    }

}
