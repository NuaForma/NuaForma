# Conditional Word object representations

Unreleased source qualification under [#242](https://github.com/NuaForma/NuaForma/issues/242).

Word can store an object more than once using `mc:AlternateContent`, with ordered `mc:Choice` branches and an optional `mc:Fallback`. A consumer selects the first choice whose required namespace URIs it understands; otherwise it uses the fallback. `Requires` contains namespace prefixes, so their actual in-scope declarations matter. See Microsoft's [Choice reference](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.alternatecontentchoice) and [Open XML primer](https://download.microsoft.com/download/e/1/4/e14fb96f-83b8-4a2a-84db-7fa8acbe061a/Office%20Open%20XML%20Part%203%20-%20Primer.pdf).

## Qualified selection

The PDF adapter understands the existing WordprocessingML, DrawingML, wordprocessing-drawing, picture and Word 2010 shape namespaces within its bounded source profile. Selection resolves aliases and namespace shadowing, propagates namespace declarations and recursively processes only selected content. Unsupported selected geometry remains an explicit failure; the adapter never chooses a different branch merely to hide that error.

Malformed containers, missing/invalid requirements, unbound prefixes, duplicate fallbacks, invalid order, unsupported compatibility attributes and misplaced branches are rejected. This profile also rejects an object with no understood choice and no fallback, rather than silently omitting an unsupported visible object. Explicitly empty selected branches retain their authored empty meaning.

DrawingML frame booleans accept their equivalent XML `0/1/false/true` spellings, including the canonical form written by the DOCX processor. Existing placement, wrapping, geometry and effect restrictions remain enforced. Recognizing the namespace does not claim support for every Word shape or drawing property.

## Template processing and diagnostics

The adapter prepares a bounded private DOCX copy before evaluating PDF-route template instructions. This prevents duplicate text, variables, blocks or syntax errors from unselected alternatives. Headers, footers and other Word XML parts use the same selection policy. Native source reading and cleanup qualification select the same representation before classifying text or formatting.

Original templates are not modified. Supported cleanup returns the original bytes. Ordinary DOCX-to-DOCX generation and unchanged round-trip behavior retain their existing path; the selection copy belongs to the PDF adapter. Development syntax diagnostics use the selected processing copy when available. If selection itself fails, inspection can attempt the original source. Archive/XML limits, cancellation, embedding and package-relationship safety remain enforced; nothing is fetched externally.

## References and regression policy

Synthetic A4/US Letter sources under `nuaforma-docx-pdf/src/test/resources/compatibility-objects` contain matching modern and legacy text-frame representations. Their actual Word exports, source/font hashes and initially reviewed engine goldens bind the references. Rebuild sources with `tools/pdf-evaluation/own-engine/build_compatibility_fixture.py --base <paragraph-metadata-directory> --output <fresh-directory>` and author independent Word PDFs with the existing exporter and Liberation Sans Regular fixture.

Standard PDF and PDF/A-1b/2b/3b must preserve Word text/glyph geometry, render each selected frame once and match both the reviewed engine goldens and an explicitly selected source exactly. Separate actual-provider tests cover selected variables/blocks, hidden malformed markers, footer alternatives, syntax inspection and rejection of selected effects. Existing goldens cannot be replaced without explicit approval and review.

The Playground inherits the provider and inspection improvement without a new control. No public model, API, dependency, runtime font bundle or package is added. Source support does not imply publication; private corpus files and local test fonts remain outside Git and CI.
