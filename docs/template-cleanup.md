# Playground template cleanup (source #191)

This development tool creates a separate DOCX template with unsupported package parts, constructs and formatting removed. It does not fill the template, interpret expressions or apply supplied data. The caller opens the new file in Word and decides whether its contents and appearance are acceptable. Automatic verification establishes structural source-profile support, not visual acceptance.

Selected for 0.4.0-alpha.2; unchanged 0.4.0-alpha.1 artifacts do not contain this feature. Ordinary DOCX/PDF generation stays strict and has no cleanup option or automatic fallback.

## Use

In the Playground, select the DOCX file and open **Clean a template for native PDF**. Choose **Clean template and download report**. Only the file is sent: JSON data, font uploads, other resources, generation options and watermarks are not sent or used. Invalid JSON in the builder does not prevent cleaning a template.

The download is `cleaned-template.zip`, containing:

- `cleaned-template.docx`: the reusable template copy, without a diagnostic/report page in its body.
- `cleanup-report.txt`: every removal/restoration, its package part and available source location, and the reminder that appearance is for the caller to assess.

Locations describe the intermediate package at the time of each change. Indices can shift after an earlier removal; no original/final Word page number is inferred.

Review the report and open the DOCX in Word. Unsupported visible objects, text or styling can be removed. Accept or reject the result yourself before replacing the original. The tool does not modify the uploaded file, retain an artifact cache, record an acceptance decision or declare the appearance correct.

Remaining template instructions, including variables, pipes, conditions, repetitions and malformed markers, remain literal. Removing a containing unsupported object can also remove its text/instructions; that object removal is reported. Template syntax and data-dependent output remain subject to normal generation checks. Required fonts must still be supplied during ordinary PDF generation.

## Technical behavior

The DOCX/PDF adapter uses the existing native source reader as its structural authority. It preserves font-family/face identities during inspection without loading/substituting render fonts or invoking the template evaluator/PDF writer. Unsupported parts are removed together with their relationship and content-type references. Unsupported XML elements/attributes use the reader's exact namespace-aware source provenance. Untouched ZIP member bytes are retained; an already-supported, consistent template is returned byte for byte.

ZIP inspection uses the same central-directory view as the DOCX facade and checks member sizes/checksums. If the sequential archive view differs, the package is repacked from that directory and the normalization is reported. This prevents checking one template while returning a different template to Word. The private inspection file is removed on success and failure.

If a required structure is absent after removal, the tool reports its restoration. Missing section geometry uses A4 and one-inch margins; resetting unsupported/missing style definitions uses an explicit Normal style with Liberation Sans 11pt and removes stale named-style references. Those changes are deliberate template edits, not a substitution policy for ordinary generation. Review them like every other change.

Only a final successful source-reader check and technical opening through the existing non-evaluating DOCX facade permit a cleaned result. Irreparable/unsafe packages, unresolved checks, cancellation and exceeded budgets return an error without a published partial template. Structural support does not guarantee that every later data value, font catalog, generated pagination or archival-validation request will succeed.

## Boundaries

The route is part of the development Playground composition and absent in production and in a development server without Playground assets. It is not advertised in the ordinary REST OpenAPI/capabilities or enabled by request options. The optional contract 0.9 `TemplateCleanupProvider` facet lets the existing provider supply the operation without making document-format internals part of the transport. Existing compatible older providers remain loadable; the new built-in PDF provider requires 0.9.

Existing admission, cancellation and request/private-file boundaries apply. Native limits remain bounded: at most 8 MiB compressed/expanded source, 4 MiB per entry and 64 archive entries, further constrained by caller limits. Editing stops after 256 profile passes or 2,048 report entries. Unsafe names, duplicate entries and malformed required package structure do not produce a cleaned artifact. The XML reader never resolves entities/DTDs. An optional XML part that cannot be parsed under that policy can be removed and reported; cancellation/resource failures are never reclassified as removable content. External relationship targets are never fetched and embedded active code is never executed. The caller owns access and retention for the downloaded document/report and development instance.

## Floating stories and paragraph marks (unreleased #203)

Qualified unwrapped floating images and existing text-frame profiles in headers/footers no longer require removing the complete story. Nonprinting paragraph-mark highlight is validated and retained without applying a background to visible runs. Zero-valued Word relative-size placeholders retain the explicit physical image extent; active nonzero relative sizing remains rejected. Unknown anchored-image children identify their own source location, permitting focused cleanup instead of removing the complete picture or footer. See the [qualified cases and limits](../tools/pdf-evaluation/own-engine/WORD_FLOATING_BANDS.md).

A linked `.dotx`/`.dotm` reference and its settings relationships may still be detached from a cleaned copy. Saved internal styles/content remain; the target is never retrieved. This is separate from deleting visible document content. Literal template markers stay literal. Cleanup still checks structural support, not fonts, template data/syntax or a universally renderable final layout.
