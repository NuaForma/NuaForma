package dev.nuaforma.docx.pdf.adapters.nativeengine

import dev.nuaforma.contracts.GenerationContext
import dev.nuaforma.docx.pdf.adapters.ooxml.ConversionXml
import dev.nuaforma.docx.pdf.adapters.ooxml.XmlElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WordEditingBookmarkTest {
    private val context = GenerationContext()
    private val xml = ConversionXml(context)
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val pair = "<w:bookmarkStart w:id=\"701\" w:name=\"_GoBack\"/><w:bookmarkEnd w:id=\"701\"/>"

    private fun document(content: String) = xml.read(("<w:document xmlns:w=\"$word\" xmlns:x=\"urn:unqualified\"><w:body>" + content + "</w:body></w:document>").toByteArray())

    private fun normalize(roots: Collection<XmlElement>) = NativeDocxBookmarks(context).normalizeEditing(roots, {}) { error(it) }

    @Test
    fun `empty unused editing ranges are validated read only then omitted without changing paragraphs`() {
        for (content in listOf("<w:p><w:r><w:t>Visible text</w:t></w:r>$pair</w:p>",
            "<w:p><w:pPr/>$pair</w:p>", "<w:p><w:r><w:t>Visible text</w:t></w:r></w:p>$pair<w:sectPr/>")) {
            val root = document(content)
            val before = xml.write(root)
            NativeDocxBookmarks(context).validateSource(root, {}) { error(it) }
            assertArrayEquals(before, xml.write(root))
            normalize(listOf(root))
            assertArrayEquals(xml.write(document(content.replace(pair, ""))), xml.write(root))
        }
    }

    @Test
    fun `simple and split complex field references and internal links preserve the editing range`() {
        val fields = listOf("<w:p><w:fldSimple w:instr=\"REF _GoBack\"/></w:p>",
            "<w:p><w:fldSimple w:instr=\"PAGEREF _GoBack\"/></w:p>",
            "<w:p><w:fldSimple w:instr=\"HYPERLINK &quot;&quot; \\l &quot;_GoBack&quot;\"/></w:p>",
            "<w:p><w:hyperlink w:anchor=\"_GoBack\"><w:r><w:t>Go back</w:t></w:r></w:hyperlink></w:p>",
            "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> REF _go</w:instrText></w:r>" +
                "<w:r><w:instrText>back </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>" +
                "<w:r><w:t>Cached</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>")
        val selection = pair.replace("/><w:bookmarkEnd", "/><w:r><w:t>Selected reference text</w:t></w:r><w:bookmarkEnd")
        for (field in fields) for (range in listOf(pair, selection)) {
            val root = document("<w:p>$range</w:p>" + field)
            val before = xml.write(root)
            normalize(listOf(root))
            assertArrayEquals(before, xml.write(root))
        }
        val root = document("<w:p>$pair</w:p>")
        val anotherStory = document(fields.last())
        val before = xml.write(root)
        normalize(listOf(root, anotherStory))
        assertArrayEquals(before, xml.write(root))
    }

    @Test
    fun `unused selections retain styled text while ordinary and unsupported parent ranges remain intact`() {
        val selected = "<w:r><w:rPr><w:b/></w:rPr><w:t>Selected text</w:t></w:r>"
        val editing = pair.replace("/><w:bookmarkEnd", "/>$selected<w:bookmarkEnd")
        val ordinary = editing.replace("_GoBack", "VisibleDestination")
        val unsupported = "<w:hyperlink>$pair</w:hyperlink>"
        for ((content, expected) in listOf(editing to selected, ordinary to ordinary, unsupported to unsupported)) {
            val root = document("<w:p>$content</w:p>")
            normalize(listOf(root))
            assertArrayEquals(xml.write(document("<w:p>$expected</w:p>")), xml.write(root))
        }
    }

    @Test
    fun `invalid original editing range properties order and duplicates are rejected`() {
        for (content in listOf(pair.replace("w:name=\"_GoBack\"", "w:name=\"_GoBack\" extra=\"1\""),
            pair.replace("<w:bookmarkEnd w:id=\"701\"/>", ""),
            pair.replace("<w:bookmarkEnd w:id=\"701\"/>", "<w:bookmarkEnd w:id=\"702\"/>"),
            pair.replace("<w:bookmarkStart", "<x:bookmarkStart"),
            pair.replace("/><w:bookmarkEnd", ">text</w:bookmarkStart><w:bookmarkEnd"))) {
            val root = document("<w:p>$content</w:p>")
            var focused: XmlElement? = null
            assertThrows(IllegalArgumentException::class.java) {
                NativeDocxBookmarks(context).validateSource(root, { focused = it }) { throw IllegalArgumentException(it) }
            }
            assertNotNull(focused)
        }
        val first = document("<w:p>$pair</w:p>")
        val second = document("<w:p>${pair.replace("701", "702")}</w:p>")
        assertThrows(IllegalStateException::class.java) { normalize(listOf(first, second)) }
    }
}
