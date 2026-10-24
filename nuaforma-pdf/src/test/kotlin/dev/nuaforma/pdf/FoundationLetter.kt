package dev.nuaforma.pdf

import java.nio.file.Files
import java.nio.file.Path

object FoundationLetter {
    internal fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(name)).use { it.readBytes() }
    internal fun fonts() = listOf("regular" to "Regular", "bold" to "Bold", "italic" to "Italic").map { (id, face) ->
        PdfFont(id, resource("/fonts/LiberationSans-$face.ttf"))
    }
    internal fun document(): PdfDocument {
        val body = PdfTextStyle("regular", 11f)
        val bold = PdfTextStyle("bold", 11f)
        val italic = PdfTextStyle("italic", 11f)
        val teal = PdfTextStyle("bold", 21f, PdfColor(0, 139, 144))
        return PdfDocument(PdfPage(595.28f, 841.89f, PdfMargins(56.7f, 56.7f, 56.7f, 56.7f)), listOf(
            PdfImage(resource("/fixtures/nuaforma-icon-transparent.png"), 54f, 54f),
            PdfParagraph(listOf(PdfTextRun("NuaForma", teal)), spaceBefore = 16f, spaceAfter = 4f),
            PdfParagraph(listOf(PdfTextRun("Eigen PDF-motor - technische basisproef", PdfTextStyle("regular", 12f))), spaceAfter = 22f),
            PdfParagraph(listOf(PdfTextRun("Aan: Afdeling Documentvoorziening", body))),
            PdfParagraph(listOf(PdfTextRun("Betreft: Bevestiging van uw aanvraag", bold)), spaceAfter = 18f),
            PdfParagraph(listOf(PdfTextRun("Geachte lezer,", body))),
            PdfParagraph(listOf(PdfTextRun("Deze synthetische brief is rechtstreeks opgebouwd met het gedeelde documentmodel. Tekst en logo worden op één pagina geplaatst, zonder Word of LibreOffice.", body))),
            PdfParagraph(listOf(PdfTextRun("Controle van tekens: café, beëindiging, coördinatie en € 1.234,56. ", body),
                PdfTextRun("Deze tekst is vet. ", bold), PdfTextRun("Deze tekst is cursief.", italic))),
            PdfParagraph(listOf(PdfTextRun("Alle gebruikte lettertypen zijn ingesloten. De tekst blijft selecteerbaar en doorzoekbaar; office en affinity blijven letterlijk uitleesbaar.", body))),
            PdfParagraph(listOf(PdfTextRun("Dit is een technische proef. DOCX-invoer, automatische paginering, tabellen en PDF/A behoren nog niet tot deze eerste stap.", italic)), spaceAfter = 22f),
            PdfParagraph(listOf(PdfTextRun("Met vriendelijke groet,", body))),
            PdfParagraph(listOf(PdfTextRun("NuaForma", bold))),
        ))
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Provide the output PDF path" }
        val target = Path.of(args[0])
        Files.createDirectories(target.toAbsolutePath().parent)
        val bytes = PdfEngine().render(document(), fonts())
        Files.write(target, bytes)
        println("Foundation letter written (${bytes.size} bytes); ordinary PDF, not PDF/A.")
    }
}
