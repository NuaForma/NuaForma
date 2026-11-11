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

Existing admission, cancellation and request/private-file boundaries apply. Native limits remain bounded: at most 8 MiB compressed/expanded source, 4 MiB per entry and 256 archive entries, further constrained by caller limits. The entry ceiling increase is unreleased source work under #297; all byte limits remain unchanged. Editing stops after 256 profile passes or 2,048 report entries. Unsafe names, duplicate entries and malformed required package structure do not produce a cleaned artifact. The XML reader never resolves entities/DTDs. An optional XML part that cannot be parsed under that policy can be removed and reported; cancellation/resource failures are never reclassified as removable content. External relationship targets are never fetched and embedded active code is never executed. The caller owns access and retention for the downloaded document/report and development instance.

## Floating stories and paragraph marks (0.4.0-alpha.4, #203)

Qualified unwrapped floating images and existing text-frame profiles in headers/footers no longer require removing the complete story. Nonprinting paragraph-mark highlight is validated and retained without applying a background to visible runs. Zero-valued Word relative-size placeholders retain the explicit physical image extent; active nonzero relative sizing remains rejected. Unknown anchored-image children identify their own source location, permitting focused cleanup instead of removing the complete picture or footer. See the [qualified cases and limits](../tools/pdf-evaluation/own-engine/WORD_FLOATING_BANDS.md).

A linked `.dotx`/`.dotm` reference and its settings relationships may still be detached from a cleaned copy. Saved internal styles/content remain; the target is never retrieved. This is separate from deleting visible document content. Literal template markers stay literal. Cleanup still checks structural support, not fonts, template data/syntax or a universally renderable final layout.

## Style revisions and focused drawing failures (0.4.0-alpha.5, #208)

Validated style-level `w:rsid` editing-session identifiers have no print behavior. They are excluded only from the request-local rendering view; their original package bytes remain in an otherwise supported template. Identifiers must contain exactly eight hexadecimal characters, with one `w:val` attribute and no duplicate element, nested content or other attributes. Malformed/foreign constructs are not silently accepted. See Microsoft's [style revision definition](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.rsid?view=openxml-3.0.1).

Development image diagnostics identify the rejected XML element (including its first child, for example `effectLst/outerShdw`) or the exact attribute name such as `xfrm/@rot`. Source coordinates point inside the real drawing in the named body/header/footer story. Attribute values are not copied into the new explanations. Ordinary generation still refuses the unsupported construct and publishes no partial PDF; production source-detail redaction is unchanged.

Cleanup can remove the identified unsupported effect element or attribute and revalidate, instead of removing the entire image or page band. This can change appearance (for example removing a shadow or rotation), so inspect the report and cleaned copy in Word. If a required geometry no longer exists or another unsupported construct remains, subsequent removals may still be necessary. No new visual effects, shape geometries or external image/template retrieval are enabled by these diagnostics.

## Picture metadata and local cleanup download (0.4.0-alpha.6 #213)

Qualified editing locks, explicit no-fill/no-line properties and the known local-DPI extension remain in supported templates. EMF description buffers are distinguished from extension fields. Unknown/active constructs still reject. See the [picture/header profile](word-picture-metadata.md). Cleanup progress, errors and the ZIP link now appear inside the cleanup block, beside its button; another template selection clears the previous cleanup link.

## Unterminated EMF descriptions (0.4.0-alpha.7, #218)

A bounded header description without its expected final UTF-16 zero is unused metadata, not image geometry. The alpha.7 reader can retain an otherwise supported logo instead of deleting its media part and drawing. Declared ranges, actual graphical records and resource limits still require validation; this does not enable general malformed EMF playback. The same policy applies to normal PDF generation and Playground cleanup, without new controls. See [the compatibility policy and tested limits](word-picture-metadata.md#bounded-description-compatibility-040-alpha7-218). The unavailable private original is not claimed verified by these public synthetic tests.

## EMF solid/null pens (unreleased #223)

Otherwise supported logos retain unused extended-pen metadata and valid null pens. Actual unsupported brush styles and bitmap data are separate explicit failures: the report identifies BrushStyle (including its recognized name), cbBmi or cbBits with the EMF record number and byte offset. Active patterns are not silently replaced by a solid line. The same source reader serves normal PDF generation and development cleanup; production redaction and controls are unchanged. See [qualified pen metadata and remaining limits](word-picture-metadata.md#solid-and-null-pen-metadata-unreleased-223).

## JPEG and label metadata (source #226)

Supported embedded JPEG pictures and validated `LabelInfo.xml` metadata remain in templates instead of being removed solely for their package type. The reader normalizes these only in its internal PDF print model; visible markings and unsupported policy/drawing behavior retain their ordinary checks. No new permissive option is introduced. See [format and security boundaries](native-picture-compatibility.md). This source behavior is not part of published alpha.7.

## Header identifiers and web-export preferences (source #227)

Validated identifier-only header shape defaults remain in templates containing supported VML frames or lines. Known `relyOnVML`/`targetScreenSz` preferences also remain; they do not select a renderer or change printed geometry. Visible/unknown defaults and malformed metadata still reject or require explicit cleanup removals. See [the source profile and regression scope](word-editor-settings.md). This is not part of published alpha.7.

## Fields and bookmark boundaries (source #228)

Qualified block-level bookmarks, string document properties, literal SET/REF fields and passive legacy checkboxes remain in templates. Structural validation uses actual definitions rather than cached results, without substituting template instructions or field values into the returned DOCX. See [field limits and verification](word-fields-and-bookmarks.md).

Source #229 retains qualified privacy/drawing-grid metadata and bounded multi-paragraph DOCPROPERTY fields without cleanup changes. Actual property values are used only during PDF conversion; cleanup still leaves template instructions and field definitions unevaluated. See the [field profile](word-fields-and-bookmarks.md#multi-paragraph-properties-source-229) and [editor settings](word-editor-settings.md#save-time-privacy-and-drawing-grid-preferences-source-229).

Source #230 retains qualified style-pane sorting, next-paragraph metadata, Chicago note definitions and ordinary automatic separator spacing. Cleanup preserves the original definitions without expanding note numbers or changing existing paragraph style choices. See the [editor](word-editor-settings.md#style-editing-preferences-source-230) and [note](../tools/pdf-evaluation/own-engine/NOTES.md#chicago-numbering-and-automatic-separators-source-230) profiles.
