# Word fields and block-level bookmarks (source #228)

This source change follows published `0.4.0-alpha.7`. It adds no public API, dependency, output profile or NuaForma template syntax.

## Bookmark boundaries

Word permits [`bookmarkEnd`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.bookmarkend?view=openxml-3.0.1) between body blocks. The native reader maps qualified body-level starts/ends to existing paragraph boundaries, including the first/last paragraph of an adjacent table. It inserts no layout paragraphs and retains original diagnostic coordinates. Destinations and page references remain supported. Nonempty ranges ending after a paragraph can include its paragraph mark; text references to block-level ends remain unqualified instead of silently losing paragraph semantics. The existing cross-paragraph text-reference restriction also remains. Duplicate, unmatched, malformed and misplaced boundaries remain explicit failures.

## Properties and assignments

`DOCPROPERTY "Report Title" \* MERGEFORMAT` reads the actual validated custom string property, with quoted names and case-insensitive lookup. Qualified custom types are `lpwstr`, `lpstr` and `bstr`. Selected textual core properties include Title, Subject, Author, Comments, Keywords, Category, ContentStatus, Version and LastSavedBy. Missing/ambiguous properties, other types and date/number pictures fail explicitly. Strings are limited to 16,384 characters without control characters, with an additional per-request expansion budget. Property text is emitted literally, without recursively evaluating template instructions.

`SET Choice "M"` defines a nonprinting literal; `REF Choice` emits it using the reference's qualified formatting. Quoted text and plain integer literals are supported. Assignments are unique per name/request, capped at 1,000 and cannot conflict with visible bookmark names. Repeated assignments, calculations, linked SET references and page references to SET variables remain unsupported. Stale display caches never supply field values.

The new field types occupy one paragraph. Existing font/script/direction limits apply to their emitted values; Hebrew static fields remain unqualified, just as forced complex-script field formatting remains unqualified. This does not change support for unused non-Latin metadata or the existing bounded Hebrew ordinary-text proof.

Only actual document stories define SET values; properties, settings and style metadata cannot activate field instructions. A nonprinting literal SET field may omit its cached result and separator. Empty property values emit nothing and do not require a font used solely by their stale cache.

## Legacy checkboxes

Complex `FORMCHECKBOX` fields retain their own `ffData`. Current `checked` overrides `default`; absence of both means unchecked. The reader uses the existing opaque vector model, with a cross for checked legacy fields, without requiring Wingdings or creating interactive PDF forms. [`sizeAuto`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.automaticallysizeformfield?view=openxml-2.12.3) follows field-character formatting; explicit size uses half-points, within 1–200 points. Color and bold line weight follow the qualified style.

Form metadata is limited to `name`, `enabled`, `calcOnExit` and `checkBox`. It executes no calculations or interaction. Macros, bindings, unknown metadata, invalid states, conflicting sizes, decoration, navigation and forced complex-script checkbox fields remain unsupported.

## Verification and Playground

Regressions cover stale caches, both checkbox states, sizes, boundaries and malformed/active constructs. A4/Letter provider tests exercise body/header properties, SET/REF, checkboxes, page destinations and cleanup. Pixel comparisons use inline bookmark boundaries and different stale caches as a control. Standard PDF and PDF/A-1b/2b/3b are independently validated. Existing golden files remain unchanged; this does not claim universal Word-field fidelity.

Two synthetic documents with explicit paragraph spacing were also exported with Microsoft Word and compared per page against the exact same source converted through the real HTTP route. All four pages retain identical normalized text. Maximum text baseline differences are 0.18 points (A4) and 0.16 points (Letter); text immediately following an automatic legacy checkbox differs horizontally by up to 1.72 points. The legacy vector glyph and its spacing are approximations, not pixel-identical Word glyphs. Receipts and comparisons belong under the ignored `build/228-word-compatibility` evaluation directory, not existing golden resources.

Playground cleanup uses the same reader and can retain qualified fields/bookmarks. No permissive mode is added. The original corpus and detailed reports stay local; first-blocker movement is reported separately from complete conversion and visual fidelity.
