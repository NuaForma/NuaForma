package dev.nuaforma.docx

import dev.nuaforma.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PackageRejectionDiagnosticsTest {
    private fun changed(part: String, transform: (String) -> String) = SyntheticDocx.parts().apply {
        this[part] = transform(getValue(part).decodeToString()).toByteArray()
    }

    @Test
    fun `package metadata and relationships preserve their actual safe rejection reasons`() {
        val types = "[Content_Types].xml"
        val rels = "_rels/.rels"
        val cases = listOf(
            changed(types) { "<wrong/>" } to RejectionReason.INVALID_DOCX_CONTENT_TYPES_ROOT,
            changed(types) { "<Types/>" } to RejectionReason.INVALID_DOCX_CONTENT_TYPES_ROOT,
            changed(types) { "" } to RejectionReason.A_DOCX_XML_PART_IS_MALFORMED_OR_CONTAINS_PROHIBITED,
            changed(types) { it.replace("PartName=\"/word/document.xml\"", "") } to RejectionReason.MISSING_CONTENT_TYPE_PART_NAME,
            changed(types) { it.replace("PartName=\"/word/document.xml\"", "PartName=\"word/document.xml\"") } to RejectionReason.INVALID_OR_DUPLICATE_CONTENT_TYPE_OVERRIDE,
            changed(types) { it.replace(Regex("ContentType=\"[^\"]+\""), "") } to RejectionReason.MISSING_CONTENT_TYPE,
            changed(types) { it.replace("Extension=\"rels\"", "") } to RejectionReason.MISSING_CONTENT_TYPE_EXTENSION,
            changed(types) { it.replace("</Types>", """<Default Extension="rels" ContentType="application/xml"/></Types>""") } to RejectionReason.DUPLICATE_DEFAULT_CONTENT_TYPE,
            changed(types) { it.replace("</Types>", """<Override PartName="/word/document.xml" ContentType="application/xml"/></Types>""") } to RejectionReason.INVALID_OR_DUPLICATE_CONTENT_TYPE_OVERRIDE,
            changed(types) { it.replace("</Types>", """<Override PartName="/PRIVATE_TOKEN.xml" ContentType="application/xml"/></Types>""") } to RejectionReason.CONTENT_TYPE_POINTS_TO_A_MISSING_PACKAGE_PART,
            SyntheticDocx.parts().apply { this["word/PRIVATE_TOKEN.unknown"] = byteArrayOf(0) } to RejectionReason.A_DOCX_PART_HAS_NO_CONTENT_TYPE,
            changed(rels) { "<wrong/>" } to RejectionReason.INVALID_RELATIONSHIP_ROOT,
            changed(rels) { "<Relationships/>" } to RejectionReason.INVALID_RELATIONSHIP_ROOT,
            changed(rels) { it.replace("Id=\"r1\"", "") } to RejectionReason.MISSING_RELATIONSHIP_ID,
            changed(rels) { it.replace("Id=\"r1\"", "Id=\"\"") } to RejectionReason.EMPTY_RELATIONSHIP_ID,
            changed(rels) { it.replace(Regex("Target=\"[^\"]+\""), "") } to RejectionReason.MISSING_RELATIONSHIP_TARGET,
            changed(rels) { it.replace(Regex("Type=\"[^\"]+\""), "") } to RejectionReason.MISSING_RELATIONSHIP_TYPE,
            changed(rels) { it.replace("Target=\"word/document.xml\"", "Target=\"word/document.xml\" TargetMode=\"PRIVATE_TOKEN\"") } to RejectionReason.INVALID_RELATIONSHIP_TARGET_MODE,
            changed(rels) { it.replace("Target=\"word/document.xml\"", "Target=\"word/styles.xml\"") } to RejectionReason.DOCX_MAIN_DOCUMENT_MUST_BE_WORD_DOCUMENT_XML,
            changed(rels) { it.replace("/officeDocument\"", "/PRIVATE_TOKEN\"") } to RejectionReason.DOCX_MUST_HAVE_EXACTLY_ONE_INTERNAL_OFFICE_DOCUMENT_RELATIONSHIP,
            changed("word/document.xml") { it.replace("<w:body>", "<w:body " + (1..257).joinToString(" ") { index -> "a$index=\"x\"" } + ">") } to RejectionReason.DOCX_XML_COMPLEXITY_BUDGET_WAS_EXCEEDED,
        )
        for ((parts, expected) in cases) reject(parts, expected)
        for (target in listOf("word/document.xml?PRIVATE_TOKEN", "word/document.xml#PRIVATE_TOKEN", "https://PRIVATE_TOKEN.example/document.xml", "//PRIVATE_TOKEN.example/document.xml", "%2FPRIVATE_TOKEN", "word\\PRIVATE_TOKEN.xml", "[PRIVATE_TOKEN")) {
            reject(changed(rels) { it.replace("Target=\"word/document.xml\"", "Target=\"$target\"") }, RejectionReason.INVALID_INTERNAL_RELATIONSHIP_TARGET)
        }
        for (type in listOf("application/x-macro", "application/x-vba", "application/x-oleObject", "application/x-activeX", "text/html")) {
            reject(changed(types) { it.replace("image/png", type) }, RejectionReason.ACTIVE_CONTENT_AND_EMBEDDED_DOCUMENTS_ARE_NOT_SUPPORTED)
        }
        for (name in listOf("/", "/PRIVATE_TOKEN", "word\\PRIVATE_TOKEN", "C:PRIVATE_TOKEN", "%2FPRIVATE_TOKEN", "word//PRIVATE_TOKEN", "./PRIVATE_TOKEN")) {
            reject(SyntheticDocx.parts().apply { this[name] = byteArrayOf(0) }, RejectionReason.DOCX_CONTAINS_AN_UNSAFE_ARCHIVE_PATH)
        }
    }

    private fun reject(parts: Map<String, ByteArray>, expected: RejectionReason) {
        val diagnostic = assertThrows(GenerationException::class.java) {
            DocxDocuments.open(SyntheticDocx.zip(parts).inputStream()).use { }
        }.diagnostics.single()
        assertSame(expected, diagnostic.rejectionReason)
        assertEquals(expected.message, diagnostic.message)
        assertFalse(diagnostic.message.contains("PRIVATE_TOKEN"))
        assertTrue(expected.remediation.isNotBlank())
    }
}
