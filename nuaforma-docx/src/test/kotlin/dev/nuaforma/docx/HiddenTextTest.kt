package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class HiddenTextTest {
    private val requirements = GenerationRequirements(FormatRequest(DocumentFormat.DOCX), FormatRequest(DocumentFormat.DOCX))
    private fun run(text: String, properties: String = "") = "<w:r><w:rPr>$properties</w:rPr><w:t xml:space=\"preserve\">$text</w:t></w:r>"
    private fun paragraph(content: String, properties: String = "") = "<w:p><w:pPr>$properties</w:pPr>$content</w:p>"
    private fun source(body: String, styles: String = "", defaults: Boolean = false): ByteArray {
        val parts = SyntheticDocx.parts()
        parts["word/document.xml"] = parts.getValue("word/document.xml").decodeToString()
            .replace(Regex("<w:body>[\\s\\S]*</w:body>"), "<w:body>$body</w:body>").toByteArray()
        parts["word/styles.xml"] = parts.getValue("word/styles.xml").decodeToString().replace("</w:styles>", "$styles</w:styles>")
            .let { if (defaults) it.replace("<w:rPrDefault><w:rPr>", "<w:rPrDefault><w:rPr><w:vanish/>") else it }.toByteArray()
        parts["word/header1.xml"] = "<w:hdr xmlns:w=\"${SyntheticDocx.W}\"/>".toByteArray()
        parts["word/footer1.xml"] = "<w:ftr xmlns:w=\"${SyntheticDocx.W}\"/>".toByteArray()
        return SyntheticDocx.zip(parts)
    }
    private fun generate(bytes: ByteArray): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        DocxProvider().generate(GenerationRequest(bytes.inputStream(), requirements, mapOf("name" to "Ada", "secret" to "MUST_NOT_BE_INSERTED")),
            output, GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)))
        return SyntheticDocx.unzip(output.toByteArray())
    }
    private fun style(id: String, type: String, hidden: Boolean? = null, parent: String? = null, default: Boolean = false) =
        "<w:style w:type=\"$type\" w:styleId=\"$id\"${if (default) " w:default=\"1\"" else ""}><w:name w:val=\"$id\"/>" +
            (parent?.let { "<w:basedOn w:val=\"$it\"/>" } ?: "") +
            (hidden?.let { "<w:rPr><w:vanish w:val=\"$it\"/></w:rPr>" } ?: "") + "</w:style>"
    private fun xml(parts: Map<String, ByteArray>) = parts.getValue("word/document.xml").decodeToString()

    @Test fun `hidden braces expressions directives and content pipes remain literal without executing`() {
        val hidden = listOf("{", "}", "{bad(}", "{secret}", "{secret | unknownContent()}", "{#if false}", "{/each}")
        val body = hidden.joinToString("") { paragraph(run(it, "<w:vanish/>")) } + paragraph(run("Visible {name}"))
        val result = xml(generate(source(body)))
        hidden.forEach { assertTrue(result.contains(it), result) }
        assertTrue(result.contains(">Ada</w:t>"))
        assertFalse(result.contains("MUST_NOT_BE_INSERTED"))
        assertTrue(result.contains("vanish"))
    }

    @Test fun `visibility honors inherited paragraph character default and direct toggle semantics`() {
        val styles = style("Hidden", "paragraph", true) + style("Inherited", "paragraph", parent="Hidden") +
            style("Unchanged", "paragraph", false, "Hidden") + style("Toggled", "paragraph", true, "Hidden") +
            style("HiddenChar", "character", true) + style("ChildChar", "character", parent="HiddenChar")
        for (id in listOf("Hidden", "Inherited", "Unchanged")) {
            val result = xml(generate(source(paragraph(run("{secret}"), "<w:pStyle w:val=\"$id\"/>"), styles)))
            assertTrue(result.contains("{secret}"), id)
            val visible = xml(generate(source(paragraph(run("{name}", "<w:vanish w:val=\"0\"/>"), "<w:pStyle w:val=\"$id\"/>"), styles)))
            assertTrue(visible.contains("Ada"), id)
        }
        assertTrue(xml(generate(source(paragraph(run("{name}"), "<w:pStyle w:val=\"Toggled\"/>"), styles))).contains("Ada"))
        assertTrue(xml(generate(source(paragraph(run("{secret}", "<w:rStyle w:val=\"ChildChar\"/>")), styles))).contains("{secret}"))
        assertTrue(xml(generate(source(paragraph(run("{secret}")), style("DefaultChar", "character", true, default=true)))).contains("{secret}"))
        assertTrue(xml(generate(source(paragraph(run("{secret}")), defaults=true))).contains("{secret}"))
        assertTrue(xml(generate(source(paragraph(run("{name}", "<w:vanish w:val=\"false\"/>")), defaults=true))).contains("Ada"))
        assertTrue(xml(generate(source(paragraph(run("{name}", "<w:webHidden/>"))))).contains("Ada"))
    }

    @Test fun `hidden directives do not create blocks and visible blocks still control their parents`() {
        val body=paragraph(run("{#if false}","<w:vanish/>")) + paragraph(run("Visible {name}")) +
            paragraph(run("{#if true}")) + paragraph(run("Inside {name}")) + paragraph(run("{/if}"))
        val result=xml(generate(source(body)))
        assertTrue(result.contains("{#if false}"))
        assertTrue(result.contains(">Ada</w:t>"))
        assertEquals(2, Regex(">Ada</w:t>").findAll(result).count())
    }

    @Test fun `hidden runs are barriers and cannot supply or manufacture visible marker tokens`() {
        val body=paragraph(run("Visible ") + run("{","<w:vanish/>") + run("{name}"))
        val result=xml(generate(source(body)))
        assertTrue(result.contains("Ada"))
        assertTrue(result.contains(">{</"))
        val error=assertThrows(GenerationException::class.java) { generate(source(paragraph(run("{na") + run("ignored","<w:vanish/>") + run("me}")))) }
        assertEquals(RejectionReason.UNCLOSED_TEMPLATE_MARKER,error.diagnostics.single().rejectionReason)
        val structural=assertThrows(GenerationException::class.java) { generate(source(paragraph(run("{#if ")+run("ignored","<w:vanish/>")+run("true}"))+paragraph(run("Body")))) }
        assertEquals(RejectionReason.UNCLOSED_TEMPLATE_MARKER,structural.diagnostics.single().rejectionReason)
    }

    @Test fun `table paragraph inheritance and hyperlink visibility remain request local`() {
        val styles=style("TableHidden","table",true)
        val body="<w:tbl><w:tblPr><w:tblStyle w:val=\"TableHidden\"/></w:tblPr><w:tr><w:tc>"+
            paragraph(run("{secret}"))+"</w:tc></w:tr></w:tbl>"+
            paragraph("<w:hyperlink>"+run("{","<w:vanish/>")+run("{name}")+"</w:hyperlink>")
        val result=xml(generate(source(body,styles)))
        assertTrue(result.contains("{secret}")); assertTrue(result.contains("Ada"))
        val pool=Executors.newFixedThreadPool(4)
        try {
            val tasks=(0 until 8).map { index -> Callable {
                val hidden=index%2==0
                val output=xml(generate(source(paragraph(run("{name}"),"<w:pStyle w:val=\"Same\"/>"),style("Same","paragraph",hidden))))
                assertTrue(output.contains(if(hidden) "{name}" else "Ada"))
            } }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally { pool.shutdownNow() }
    }

    @Test fun `diagnostic skips hidden syntax and marks the actual visible failure`() {
        val output=ByteArrayOutputStream()
        val diagnostic=DocxProvider().diagnosticCopy(GenerationRequest(source(paragraph(run("{","<w:vanish/>"))+paragraph(run("Visible {bad"))).inputStream(),requirements),
            output,GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)),"Arial")
        assertEquals("paragraph:2",diagnostic.documentLocation)
        assertEquals("{bad",diagnostic.templateContext!!.expression)
        val result=xml(SyntheticDocx.unzip(output.toByteArray()))
        assertTrue(result.contains("Visible "))
        assertTrue(result.contains("[ERROR 1] {bad"))
    }

    @Test fun `invalid visibility inheritance fails boundedly before publication`() {
        for(styles in listOf(style("Cycle","paragraph",true,parent="Cycle"),style("Cycle","character",true),style("Unused","paragraph",true))) {
            val error=assertThrows(GenerationException::class.java) {
                generate(source(paragraph(run("{name}"),"<w:pStyle w:val=\"Cycle\"/>"),styles))
            }
            assertEquals(RejectionReason.DOCX_STRUCTURE_CANNOT_BE_TRANSFORMED_SAFELY_SIMPLIFY_THE_TEMPLATE,error.diagnostics.single().rejectionReason)
        }
    }
    @Test fun `document properties field instructions and hidden header text do not activate markers`() {
        val parts = SyntheticDocx.unzip(source(paragraph(run("{name}") + "<w:fldSimple w:instr=\"{\">"+run("Field result")+"</w:fldSimple>"))).toMutableMap()
        parts["docProps/core.xml"] = parts.getValue("docProps/core.xml").decodeToString().replace("DOCX round trip reference", "{").toByteArray()
        for ((name, tag) in listOf("header1" to "hdr", "footer1" to "ftr")) {
            parts["word/$name.xml"] = ("<w:$tag xmlns:w=\"${SyntheticDocx.W}\">"+paragraph(run("{secret | unknownContent()}","<w:vanish/>")+run("{name}"))+"</w:$tag>").toByteArray()
        }
        val result = generate(SyntheticDocx.zip(parts))
        assertTrue(result.getValue("docProps/core.xml").decodeToString().contains(">{</dc:title>"))
        assertTrue(xml(result).contains("Field result"))
        for(name in listOf("header1", "footer1")) {
            val value=result.getValue("word/$name.xml").decodeToString()
            assertTrue(value.contains("{secret | unknownContent()}"))
            assertTrue(value.contains(">Ada</w:t>"))
        }
    }

    @Test fun `diagnostic explanation stays visible when document defaults hide ordinary text`() {
        val output=ByteArrayOutputStream()
        val bytes=source(paragraph(run("{bad", "<w:vanish w:val=\"false\"/>")), defaults=true)
        DocxProvider().diagnosticCopy(GenerationRequest(bytes.inputStream(),requirements),output,
            GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)),"Arial")
        val result=xml(SyntheticDocx.unzip(output.toByteArray()))
        assertTrue(result.contains("TEMPLATE DIAGNOSTIC - ERROR 1"))
        assertTrue(result.contains("[ERROR 1] {bad"))
    }

    @Test fun `conditional table visibility is rejected rather than activating ambiguous instructions`() {
        val styles="<w:style w:type=\"table\" w:styleId=\"Conditional\"><w:name w:val=\"Conditional\"/>"+
            "<w:tblStylePr w:type=\"firstRow\"><w:rPr><w:vanish/></w:rPr></w:tblStylePr></w:style>"
        val body="<w:tbl><w:tblPr><w:tblStyle w:val=\"Conditional\"/></w:tblPr><w:tr><w:tc>"+paragraph(run("{name}"))+"</w:tc></w:tr></w:tbl>"
        val failure=assertThrows(GenerationException::class.java) { generate(source(body,styles)) }
        assertEquals(RejectionReason.DOCX_STRUCTURE_CANNOT_BE_TRANSFORMED_SAFELY_SIMPLIFY_THE_TEMPLATE,failure.diagnostics.single().rejectionReason)
    }

    @Test fun `structural diagnostic uses a visible anchor after hidden prefix text`() {
        val hidden=run("{secret}","<w:vanish/><w:rFonts w:ascii=\"Missing Hidden Font\" w:hAnsi=\"Missing Hidden Font\"/>")
        val visible=run("{#if true}","<w:rFonts w:ascii=\"Marker Family\" w:hAnsi=\"Marker Family\"/>")
        val output=ByteArrayOutputStream()
        val diagnostic=DocxProvider().diagnosticCopy(GenerationRequest(source(paragraph(hidden+visible)+paragraph(run("Body"))).inputStream(),requirements),
            output,GenerationContext(GenerationLogging(LoggingMode.DEVELOPMENT)),"Arial")
        assertEquals(RejectionReason.UNCLOSED_DOCX_CONDITION,diagnostic.rejectionReason)
        val result=xml(SyntheticDocx.unzip(output.toByteArray()))
        val label=Regex("<w:r>[\\s\\S]*?</w:r>").findAll(result).map { it.value }.firstOrNull { it.contains("[ERROR 1]") }
        assertNotNull(label,result)
        assertTrue(label!!.contains("Marker Family"),label)
        assertFalse(label.contains("Missing Hidden Font"),label)
    }

}
