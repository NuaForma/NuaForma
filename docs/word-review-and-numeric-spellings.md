# Word print-source metadata and numeric spellings

Unreleased source work #273–#278 stays inside the existing DOCX/PDF adapter. It adds no public API, module, dependency or font substitution. Existing published versions remain unchanged.

## Review annotations

The PDF conversion uses a request-local print copy. It validates and detaches qualified text comments and their known modern sidecars before evaluating template instructions. Comment range markers do not interrupt visible expressions. Annotation-only text, character styles and font names never become visible content or template instructions. The original bytes remain unchanged; DOCX-to-DOCX processing and template cleanup retain supported annotations.

The bounded graph includes `comments.xml`, `commentsExtended.xml`, `commentsIds.xml`, `commentsExtensible.xml` and `people.xml`, their document relationships and content types. Well-formed unused modern identities and contact metadata may remain after Word deleted the actual comments. Contact identifiers remain inert strings; nothing is fetched. Visible anchors must resolve to real comments, use a consistent story and preserve valid range order. Missing, duplicate, external or active constructs fail explicitly before output publication. Complex comments containing fields, images or dependencies remain outside this qualification.

Synthetic A4 and Letter controls cover classic comments, modern replies, stale empty caches, nonprinting reference styles and a visible expression split by comment anchors. Real Word exports without markup retain the existing preferred-table-width control pixels. Native comparisons reuse those protected goldens; they do not replace them. Source diagnostics identify the offending original part and element in development mode. Cancellation, entry, node and text budgets remain enforced.

## View settings and package previews

Qualified `displayBackgroundShape` and `doNotShadeFormData` on/off settings describe the editing view. A plain `writeProtection` recommendation describes opening the source read-only. They do not introduce PDF layout instructions and are omitted from the render representation after original-source validation. Password/crypto attributes, unknown values, duplicate declarations, foreign attributes and nested payloads remain explicit failures. Actual document backgrounds, form content and other dependent constructs retain their separate source guards. This does not add visible backgrounds or form controls.

The closed package-thumbnail profile now includes `docProps/thumbnail.emf` with `image/x-emf`, alongside JPEG/PNG. A single internal package-thumbnail relationship, matching content type and nonempty opaque payload remain required. These bytes are file-browser metadata: they are never decoded as printed images, treated as template instructions or used for font selection. Missing relationship roots, external or mismatched targets, multiple previews and empty payloads reject. This changes no visible EMF drawing rules and adds no image dependency.

The caller's bytes, supported cleanup metadata and ordinary DOCX processing remain intact. The synthetic view/preview Word exports have exactly the existing preferred-table-width reference pixels on both papers. The corresponding native tests reuse protected goldens.

## Explicit horizontal section flow

An explicit section `textDirection` of `lrTb` expresses the engine's existing left-to-right, top-to-bottom page flow. Original namespace, attributes, uniqueness and leaf structure are checked before this default is removed from the render view. Paragraph bidi and section geometry remain unchanged. Vertical, rotated and unknown section flow and RTL gutters retain their restrictions. The independent Word controls match the existing references exactly; this is no claim of new vertical-text support.

See Microsoft's definitions of [background display](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.displaybackgroundshape), [form-field indicators](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.donotshadeformdata), [read-only recommendations](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.writeprotection) and [section flow values](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.textdirectionvalues).

## Fractional layout values

Word-generated documents can contain fractional decimal spellings in fields normally bound as integers. Qualified table-grid widths, physical table-default cell margins, physical table indentation and automatic paragraph line spacing are validated before binding, then truncated to integers. For example `819.2` becomes `819` twips and automatic spacing `252.00000000000003` becomes `252` relative units. These are adapter rules, not changes to the format-neutral document model.

Eighteen independent Word exports compare raw, truncated and rounded-up controls for three features on A4 and Letter. Each raw reference has exactly the same pixels as its truncated control and differs from the rounded-up control. Bounds apply before truncation, so an out-of-range fraction cannot become valid by discarding its decimal part. Unsupported units, duplicate/foreign properties, malformed spellings and fractional non-automatic line spacing remain explicit errors. This does not accept arbitrary fractional Word properties.

For table indentation, both direct and inherited `0.0` retain an explicitly declared zero. The declaration must not be removed: legacy Word distinguishes it from an omitted indent. Positive fractions retain the independently checked integer placement. [Word's recorded parsed table model](../nuaforma-docx-pdf/src/test/resources/fractional-table-indent/word-parser-observation.jsonl) turns `100.8` into `100` twips in compatibility modes 12 and 15; the raw and truncated Word PDFs match. A rounded-up control has a different parsed position even when an older Word layout quantizes the small difference in its PDF. Original bounds apply before truncation, and unsupported units and duplicate properties reject.

Unknown numeric-property attributes are reported individually so Playground cleanup can remove an invalid attribute while retaining valid siblings such as paragraph spacing. The existing cleanup regression asserts this behavior unchanged.

Playground cleanup writes qualified numeric spellings back as canonical attributes in the returned template and reports the normalization. This keeps the cleaned DOCX reusable through both the DOCX binding and PDF routes. It preserves template instructions, valid review annotations and untouched package-part bytes; it does not replace the template with the metadata-stripped print copy. Orphan EMF package previews rejected by structural source inspection can be removed without treating their binary bytes as XML.

## Qualification

The normal full build passes with 1,655 tests and no failures, errors or skips. Libraries and output validation run on Java 17; the server and Playground run on Java 25. The six changes add no public model or dependency.

Real HTTP qualification covers 78 PDFs across standard PDF and PDF/A-1b/2b/3b. All 68 assigned native golden pages match exactly; independent Word comparisons retain the same text and paper geometry, with 1,182 measured anchors within 0.131 point horizontally and 0.170 point vertically. All 41 focused negative requests reject with original-source diagnostics and publish no PDF. Fourteen separate real Playground cleanup/download/reuse routes are idempotent and retain exact PDF pixels, text and embedded fonts.

Sixty synthetic source fixtures reproduce through their maintained builders. Fifty-eight retain exact-source Word export receipts; two expression fixtures declare their separate post-substitution Word controls. Ten new initial native reference pages were reviewed. Existing protected golden files were not replaced.

The unchanged local 250-document corpus converts ten documents into fifteen pages. All fifteen retain exact pixels, text and page counts against the preceding native run, with embedded fonts. The preceding group had added one two-page conversion; those pages were reviewed for legibility and physical bounds. This corpus comparison is a regression check, not a Word-fidelity assertion for the private original documents. Remaining unsupported constructs, fonts, resource limits and template-data requirements remain explicit.

No release, website publication or original sample/font distribution is part of this work. The Playground uses the same provider without a new control or permissive fallback.
