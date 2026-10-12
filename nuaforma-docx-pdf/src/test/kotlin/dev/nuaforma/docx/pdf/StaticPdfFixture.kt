package dev.nuaforma.docx.pdf

import java.io.ByteArrayOutputStream

/** Independently written PDF syntax; not renderer output or a visual reference. */
internal object StaticPdfFixture {
    fun bytes(catalog: String = "", page: String = "", extra: List<String> = emptyList()): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R $catalog >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] $page >>",
        ) + extra
        val output = ByteArrayOutputStream()
        fun write(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        write("%PDF-1.7\n")
        val offsets = objects.mapIndexed { index, value ->
            output.size().also { write("${index + 1} 0 obj\n$value\nendobj\n") }
        }
        val xref = output.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }
}
