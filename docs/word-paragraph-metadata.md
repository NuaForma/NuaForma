# Paragraph alignment, inherited language and style display names

Unreleased source qualification under [#238](https://github.com/NuaForma/NuaForma/issues/238), [#239](https://github.com/NuaForma/NuaForma/issues/239) and [#240](https://github.com/NuaForma/NuaForma/issues/240).

## Text baselines

`w:textAlignment` controls vertical character alignment within each line. The reader accepts explicit `baseline` alignment and qualifies `auto` for Latin, Greek, Cyrillic and ordinary numbers. Automatic alignment stays unsupported where another script, East Asian punctuation/fullwidth forms or forced complex-script formatting requires separate qualification. The conservative guard includes stories, page bands, notes and potential decoded field text, even when a cached field value looks harmless. Unsupported top/center/bottom alignment remains explicit.

Required values, unique paragraph properties, qualified namespaces and leaf structure are validated before private normalization. Source and supported cleanup bytes retain the original properties. See Microsoft's [TextAlignment](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.textalignment) and [VerticalTextAlignmentValues](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.verticaltextalignmentvalues) references.

## Inherited run language

A missing Latin `w:lang/@w:val` retains the current inherited language. This also handles the empty private node left when inactive East Asian/bidi language metadata is normalized. Defaults and styles still apply, and explicit Latin values still override and pass the existing language-tag validation. No replacement language is invented; active complex-script restrictions, font requirements and malformed/foreign-node checks retain their behavior.

The [Open XML SDK schema](https://github.com/dotnet/Open-XML-SDK/blob/main/data/schemas/schemas_openxmlformats_org_wordprocessingml_2006_main.json) defines the Latin, East Asian and complex-script language attributes as optional. Microsoft's [Languages reference](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.languages) describes their separate roles.

## Alternate display names

Validated `w:aliases` supply alternate style names for the editor. They do not replace style IDs during native lookup. One leaf element per style is accepted with required `w:val`, at most 253 Unicode characters and no foreign attributes or nested content. Comma-separated, empty and Unicode values are supported. Source/cleanup bytes retain the aliases; only the private print model omits this passive metadata. Invalid style-ID references remain errors.

See Microsoft's [Aliases reference](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.aliases) and [style ID/name/alias explanation](https://learn.microsoft.com/en-us/office/open-xml/word/how-to-create-and-add-a-paragraph-style-to-a-word-processing-document).

## Verification and integration

Synthetic A4/US Letter sources combine the three features with inherited language, signed/hanging indents and mixed 8/12/20pt runs on an explicit 36pt line. Mixed natural line boxes remain separately unsupported under [#241](https://github.com/NuaForma/NuaForma/issues/241). Actual Word exports and provenance live under `nuaforma-docx-pdf/src/test/resources/paragraph-metadata`. Rebuild sources with `tools/pdf-evaluation/own-engine/build_paragraph_metadata_fixture.py --base <right-indent-adjustment-directory> --output <fresh-directory>`; author references with the existing Word exporter and Liberation Sans Regular fixture.

The four output profiles are checked against Word text/glyph geometry, initially reviewed immutable engine goldens and otherwise identical inputs without these properties. Existing goldens must not be replaced to hide regressions. The Playground uses the same provider and cleanup qualification; there is no new control. Public models, APIs, dependencies and runtime font bundle are unchanged. Private corpus documents/fonts remain local; source support does not imply publication.
