package dev.nuaforma.pdf

/** Synthetic inputs for engine-only golden images. No source-format reader participates. */
internal object PdfVisualFixtures {
    data class Case(val id: String, val document: PdfDocument, val pages: Int, val requiredText: List<String>)

    private val teal = PdfColor(0, 139, 144)
    private val pale = PdfColor(229, 244, 245)
    private val body = PdfTextStyle("regular", 11f)
    private val bold = body.copy(fontId = "bold")
    private val a4 = PdfPage(595.28f, 841.89f, PdfMargins(72f, 54f, 64f, 54f))
    private fun p(text: String, style: PdfTextStyle = body, format: PdfParagraphFormat = PdfParagraphFormat(exactLineHeight = 16f)) =
        PdfParagraph(listOf(PdfTextRun(text, style)), spaceAfter = 8f, format = format)
    private fun heading(text: String) = p(text, bold.copy(size = 19f, color = teal), PdfParagraphFormat(exactLineHeight = 26f))
    private fun logo(size: Float) = PdfImage(FoundationLetter.resource("/fixtures/nuaforma-icon-transparent.png"), size, size)
    private fun cell(text: String, header: Boolean = false, span: Int = 1, rowSpan: Int = 1) =
        PdfTableCell(listOf(p(text, if (header) bold else body)), if (header) pale else null, span, rowSpan)

    private fun bands(): PdfFlow {
        val header = PdfParagraph(listOf(PdfImageRun(logo(20f)), PdfTextRun("  NuaForma | engine reference", bold)),
            spaceAfter = 0f, format = PdfParagraphFormat(exactLineHeight = 26f))
        val footer = PdfParagraph(listOf(PdfImageRun(logo(12f)), PdfTextRun("  Fixed fixture | Page ", body),
            PdfPageNumber(body), PdfTextRun(" of ", body), PdfPageCount(body)), spaceAfter = 0f,
            format = PdfParagraphFormat(exactLineHeight = 18f))
        return PdfFlow(PdfPageBand(22f, 30f, listOf(header)), PdfPageBand(22f, 22f, listOf(footer)))
    }

    fun cases(): List<Case> = listOf(letter(), report(), merged(), splitRows(), lists(), frames(), international(),
        formats(), keeps(), tableEdges(), automaticSpacing(), anchoredImages(), croppedImage())

    private fun anchoredImages(): Case {
        val text = "The image remains associated with its paragraph. Text uses the available region alongside it and returns to the complete body width below it. "
        val anchors = listOf(PdfImageWrap.RIGHT to 54f, PdfImageWrap.LEFT to 469.28f, PdfImageWrap.BOTH to 240f)
        val blocks = listOf(heading("Paragraph images and rectangular wrapping")) + anchors.mapIndexed { index, (wrap, left) ->
            PdfParagraph(listOf(PdfTextRun("Region ${index + 1}. " + text.repeat(4), body)), spaceAfter = 18f,
                format = PdfParagraphFormat(exactLineHeight = 16f, widowControl = true), anchoredImages = listOf(
                    PdfAnchoredImage(logo(72f), left, 0f, wrap = wrap, distances = PdfMargins(0f, 6f, 6f, 6f))))
        }
        return Case("anchored-image-wrap", PdfDocument(a4, blocks, bands()), 1, listOf("Region 1.", "Region 2.", "Region 3."))
    }

    private fun croppedImage(): Case {
        val image = PdfImage(logo(96f).bytes(), 96f, 72f, PdfImageCrop(top = .1f, right = .15f, bottom = .2f, left = .2f))
        val text = "Cropped source pixels are clipped to the declared physical rectangle. Text must remain beside this image, with no lost words or accidental scaling of the page. "
        val paragraph = PdfParagraph(listOf(PdfTextRun(text.repeat(6), body)), format = PdfParagraphFormat(exactLineHeight = 16f),
            anchoredImages = listOf(PdfAnchoredImage(image, 54f, 0f, wrap = PdfImageWrap.RIGHT, distances = PdfMargins(0f, 8f, 4f, 0f))))
        return Case("cropped-image-wrap", PdfDocument(PdfPage(612f, 792f, a4.margins), listOf(heading("Cropped image on US Letter"), paragraph), bands()), 1,
            listOf("Cropped source pixels", "no lost words"))
    }

    private fun letter(): Case {
        val mixed = PdfParagraph(listOf(PdfTextRun("Real bold ", bold), PdfTextRun("and italic. ", body.copy(fontId = "italic")),
            PdfTextRun("Accents: café, coördinatie, € 1.234,56.", body)), format = PdfParagraphFormat(exactLineHeight = 16f))
        val blocks = listOf(logo(48f), heading("A4 correspondence"), p("Reference: NF-2026-001", bold),
            p("Dear reader,"), p("This fixed document is built directly from the shared document model. It checks the logo, embedded fonts, wrapping and paragraph alignment."),
            mixed, p("Centered confirmation", format = PdfParagraphFormat(PdfAlignment.CENTER, exactLineHeight = 16f)),
            p("Right aligned amount: € 1.234,56", format = PdfParagraphFormat(PdfAlignment.RIGHT, exactLineHeight = 16f)),
            p("A hanging paragraph keeps its continuation lines aligned with the body indent. This sentence is deliberately long enough to wrap onto another line.",
                format = PdfParagraphFormat(leftIndent = 30f, firstLineIndent = -18f, exactLineHeight = 16f)),
            p("Kind regards,"), p("NuaForma", bold))
        return Case("a4-letter", PdfDocument(a4, blocks, bands()), 1, listOf("NF-2026-001", "café", "NuaForma"))
    }

    private fun report(): Case {
        val rows = listOf(PdfTableRow(listOf(cell("Item", true), cell("Description", true), cell("Amount", true)))) +
            (1..36).map { index -> PdfTableRow(listOf(cell("R%02d".format(java.util.Locale.ROOT, index)),
                cell("Fixed report entry $index with a second sentence for predictable wrapping."), cell("EUR $index.00"))) }
        val table = PdfTable(listOf(60f, 330f, 97.28f), rows, repeatedHeaderRows = 1)
        return Case("multipage-report", PdfDocument(a4, listOf(heading("Report with repeated table headers"), table), bands()),
            3, listOf("R01", "R36", "Page 3 of 3"))
    }

    private fun merged(): Case {
        val nested = PdfTable(listOf(135f, 135f), listOf(
            PdfTableRow(listOf(cell("Nested A", true), cell("Nested B", true))),
            PdfTableRow(listOf(cell("Value A"), cell("Value B")))))
        val table = PdfTable(listOf(120f, 183.64f, 183.64f), listOf(
            PdfTableRow(listOf(cell("Merged heading", true, span = 3))),
            PdfTableRow(listOf(cell("Vertical span", rowSpan = 2), cell("Upper middle"), cell("Upper right"))),
            PdfTableRow(listOf(cell("Lower middle"), cell("Lower right"))),
            PdfTableRow(listOf(cell("Nested table"), PdfTableCell(listOf(p("Inner grid"), nested), columnSpan = 2)))))
        return Case("merged-nested-table", PdfDocument(a4, listOf(heading("Merged cells and nested table"), table), bands()),
            1, listOf("Vertical span", "Lower right", "Value B"))
    }

    private fun splitRows(): Case {
        val row = PdfTableRow(listOf(PdfTableCell((1..55).map { p("Left line $it") }),
            PdfTableCell((1..55).map { p("Right line $it") })), allowSplit = true)
        val table = PdfTable(listOf(243.64f, 243.64f), listOf(
            PdfTableRow(listOf(cell("Left continuation", true), cell("Right continuation", true))), row), repeatedHeaderRows = 1)
        return Case("fragmented-table-row", PdfDocument(a4, listOf(table), bands()),
            2, listOf("Left line 1", "Right line 55", "Page 2 of 2"))
    }

    private fun lists(): Case {
        val definition = PdfListDefinition("chapters", listOf(PdfListLevel(PdfNumberFormat.DECIMAL, "%1."),
            PdfListLevel(PdfNumberFormat.DECIMAL, "%1.%2."), PdfListLevel(PdfNumberFormat.LOWER_LETTER, "%3)")))
        val steps = listOf(0, 1, 2, 2, 1, 0, 1).map { PdfListStep("chapters", it) } + PdfListStep("chapters", 0, restart = true)
        val values = PdfNumbering.resolve(listOf(definition), steps)
        val entries = values.mapIndexed { index, value ->
            val indent = 36f + value.level * 30f
            PdfParagraph(listOf(PdfTextRun("Entry ${index + 1}: fixed text with a continuation line to verify hanging indentation and marker positioning at each level.", body)),
                spaceAfter = 10f, format = PdfParagraphFormat(leftIndent = indent, firstLineIndent = -24f, exactLineHeight = 16f),
                listLabel = PdfListLabel(value, bold, tabStop = indent))
        }
        val tabs = PdfParagraph(listOf(PdfTextRun("Total", bold), PdfTab(body), PdfTextRun("123.45", body)),
            format = PdfParagraphFormat(exactLineHeight = 16f, tabs = PdfTabSettings(listOf(PdfTabStop(430f, PdfTabAlignment.DECIMAL, PdfTabLeader.DOT)))))
        return Case("lists-and-tabs", PdfDocument(a4, listOf(heading("Levels, restart and tab leader")) + entries + tabs, bands()),
            1, listOf("Entry 8", "123.45"))
    }

    private fun frames(): Case {
        val page = PdfPage(612f, 792f, PdfMargins(72f, 72f, 64f, 54f))
        val boxes = listOf(
            PdfTextBox(70f, 190f, 310f, 90f, listOf(p("Centered callout", bold), p("A fixed frame with fill and border.")),
                PdfBoxAnchor.PAGE, fill = pale, border = PdfBorder(1f, teal), verticalAlignment = PdfVerticalAlignment.CENTER),
            PdfTextBox(420f, 330f, 180f, 40f, listOf(p("MARGIN REFERENCE 123", bold)), PdfBoxAnchor.PAGE, rotation = 90f,
                border = PdfBorder(.5f, teal)))
        return Case("letter-rotated-frames", PdfDocument(page, listOf(heading("US Letter: text frames"),
            p("The callout and rotated margin text use page anchors. Header and footer each contain the same approved logo.")) + boxes, bands()),
            1, listOf("Centered callout", "MARGIN REFERENCE 123"))
    }

    private fun international(): Case {
        val page = PdfPage(792f, 612f, PdfMargins(72f, 54f, 64f, 54f))
        val hebrew = p("שלום עולם 123", body.copy(languageTag = "he-IL"),
            PdfParagraphFormat(PdfAlignment.RIGHT, exactLineHeight = 18f, textDirection = PdfTextDirection.RIGHT_TO_LEFT))
        return Case("letter-landscape-scripts", PdfDocument(page, listOf(heading("US Letter landscape: qualified scripts"),
            p("Отчёт о ходе проекта. Ёлка, сумма 1 234,56.", body.copy(languageTag = "ru-RU")),
            p("Київ, Україна. Ґрунт, єдність, їжа.", body.copy(languageTag = "uk-UA")),
            p("Έκθεση προόδου. Αθήνα, στοιχεία, μορφοποίηση.", body.copy(languageTag = "el-GR")),
            hebrew, p("Hebrew uses the bounded unpointed RTL profile; Arabic shaping remains unsupported.")), bands()),
            1, listOf("Отчёт", "Київ", "Έκθεση", "שלום עולם 123"))
    }

    private fun formats(): Case {
        val policies = listOf("A3 portrait" to a4.copy(width = 841.89f, height = 1190.55f),
            "US Legal portrait" to a4.copy(width = 612f, height = 1008f),
            "A4 landscape" to a4.copy(width = 841.89f, height = 595.28f))
        val sequences = policies.map { (label, page) ->
            val width = page.width - page.margins.left - page.margins.right
            PdfPageSequence(page, listOf(heading(label), p("Each section starts on its own physical page. Global page numbers continue across changes of paper size."),
                PdfTable(listOf(width / 2, width / 2), listOf(PdfTableRow(listOf(cell("Left edge", true), cell("Right edge", true))))),
                p("Right margin marker", format = PdfParagraphFormat(PdfAlignment.RIGHT, exactLineHeight = 16f))), bands())
        }
        return Case("mixed-paper-sections", PdfDocument(sequences), 3, listOf("A3 portrait", "US Legal portrait", "A4 landscape", "Page 3 of 3"))
    }

    private fun keeps(): Case {
        val page = PdfPage(300f, 240f, PdfMargins(24f, 24f, 24f, 24f))
        val filler = (1..7).map { p("Leading paragraph $it") }
        val keepHeading = p("Heading stays with its text", bold, PdfParagraphFormat(exactLineHeight = 16f, keepWithNext = true))
        val kept = p("This paragraph wraps across several lines and stays together with its heading on the next page.",
            format = PdfParagraphFormat(exactLineHeight = 16f, keepLines = true, widowControl = true))
        return Case("paragraph-keeps", PdfDocument(page, filler + keepHeading + kept, PdfFlow()), 2,
            listOf("Leading paragraph 7", "Heading stays with its text", "next page."))
    }

    private fun tableEdges(): Case {
        val red = PdfEdgeStroke(PdfBorder(2f, PdfColor(180, 40, 50)), priority = 2)
        val blue = PdfEdgeStroke(PdfBorder(1f, PdfColor(30, 70, 140)), priority = 1)
        val cells = listOf(
            PdfTableCell(listOf(p("Top")), pale, verticalAlignment = PdfVerticalAlignment.TOP,
                borders = PdfCellBorders(top = red, right = red, bottom = blue, left = blue)),
            PdfTableCell(listOf(p("Center")), verticalAlignment = PdfVerticalAlignment.CENTER),
            PdfTableCell(listOf(p("Bottom")), pale, verticalAlignment = PdfVerticalAlignment.BOTTOM),
            PdfTableCell((1..5).map { p("Height $it") }))
        val table = PdfTable(List(4) { 115f }, listOf(PdfTableRow(cells)), leftIndent = 10f)
        return Case("cell-alignment-borders", PdfDocument(a4, listOf(heading("Cell alignment and edge precedence"), table), bands()),
            1, listOf("Top", "Center", "Bottom", "Height 5"))
    }

    private fun automaticSpacing(): Case {
        val paragraphs = listOf(1f, 1.5f, 2f).flatMap { multiple -> listOf(p("Natural spacing: $multiple", bold),
            p("Natural font metrics determine line spacing. The same fixed text wraps onto several lines in each sample, leaving visible space between consecutive baselines.",
                format = PdfParagraphFormat(rightIndent = 180f, automaticLineSpacing = PdfAutomaticLineSpacing(multiple)))) }
        return Case("natural-line-spacing", PdfDocument(a4, listOf(heading("Natural font metrics")) + paragraphs, bands()),
            1, listOf("Natural spacing: 1.0", "Natural spacing: 1.5", "Natural spacing: 2.0"))
    }
}
