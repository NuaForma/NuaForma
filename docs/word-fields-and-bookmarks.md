# Word fields and block-level bookmarks (source #228 / #229)

This source change follows published `0.4.0-alpha.7`. It adds no public API, dependency, output profile or NuaForma template syntax.

## Bookmark boundaries

Word permits [`bookmarkEnd`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.bookmarkend?view=openxml-3.0.1) between body blocks. The native reader maps qualified body-level starts/ends to existing paragraph boundaries, including the first/last paragraph of an adjacent table. It inserts no layout paragraphs and retains original diagnostic coordinates. Destinations and page references remain supported. Nonempty ranges ending after a paragraph can include its paragraph mark; text references to block-level ends remain unqualified instead of silently losing paragraph semantics. The existing cross-paragraph text-reference restriction also remains. Duplicate, unmatched, malformed and misplaced boundaries remain explicit failures.

## Properties and assignments

`DOCPROPERTY "Report Title" \* MERGEFORMAT` reads the actual validated custom string property, with quoted names and case-insensitive lookup. Qualified custom types are `lpwstr`, `lpstr` and `bstr`. Selected textual core properties include Title, Subject, Author, Comments, Keywords, Category, ContentStatus, Version and LastSavedBy. Missing/ambiguous properties, other types and date/number pictures fail explicitly. Decoded strings are limited to 16,384 characters, with an additional per-request expansion budget. Qualified paragraph separators are described below; other control characters remain unsupported. Property text is emitted literally, without recursively evaluating template instructions.

`SET Choice "M"` defines a nonprinting literal; `REF Choice` emits it using the reference's qualified formatting. Quoted text and plain integer literals are supported. Assignments are unique per name/request, capped at 1,000 and cannot conflict with visible bookmark names. Repeated assignments, calculations, linked SET references and page references to SET variables remain unsupported. Stale display caches never supply field values.

SET and legacy checkbox fields occupy one paragraph. Source #229 additionally qualifies bounded multi-paragraph DOCPROPERTY results. Existing font/script/direction limits apply to their emitted values; Hebrew static fields remain unqualified, just as forced complex-script field formatting remains unqualified. This does not change support for unused non-Latin metadata or the existing bounded Hebrew ordinary-text proof.

Only actual document stories define SET values; properties, settings and style metadata cannot activate field instructions. A nonprinting literal SET field may omit its cached result and separator. Empty property values emit nothing and do not require a font used solely by their stale cache.

## Multi-paragraph properties (source #229)

Actual property strings may contain CR or CRLF paragraph separators, including Word's `_x000d_` escape followed by LF. Office `_xHHHH_` escapes are decoded once; an escaped underscore does not activate a second escape. Bare LF is a distinct Word text construct and remains unqualified. Invalid control characters, unpaired surrogates and excessive values reject explicitly.

Simple and complex DOCPROPERTY fields may replace up to 64 cached paragraphs with up to 64 actual paragraphs. Prefix and suffix text, whole-result bookmark boundaries and trailing empty paragraphs remain. Cached text is never the value source. As observed with Word field updates, the final paragraph keeps the ending paragraph's properties; the first two non-final inserted paragraphs use the starting paragraph's properties, and further intermediate paragraphs use document defaults. Direct result-run formatting must be uniform; each new paragraph resolves inherited character formatting in its own paragraph style.

Nested fields, partial result bookmarks, overlapping expanded fields sharing a paragraph, active or non-text result objects, locked fields and direct numbering/section ownership remain explicit failures. Existing font and direction qualification still applies. This is a bounded field profile, not a general field-code interpreter. Structural, provider, cleanup and A4/Letter output-profile tests cover changed paragraph counts, empty values, prefix/suffix text and rejected structures. Exact-source Word exports are reviewed locally; existing golden files are not replaced.

Four synthetic A4/Letter cases, each two pages, were exported with actual Word field updates and compared against the same DOCX bytes rendered by the engine. All eight pages have identical normalized text. Maximum measured baseline difference is 0.10 points and maximum word-start difference is 0.30 points. The cases include expansion beyond the cached paragraph count, trailing empty paragraphs, differing paragraph styles and text outside the field. This does not extend the claim to arbitrary field formatting.

## Legacy checkboxes

Complex `FORMCHECKBOX` fields retain their own `ffData`. Current `checked` overrides `default`; absence of both means unchecked. The reader uses the existing opaque vector model, with a cross for checked legacy fields, without requiring Wingdings or creating interactive PDF forms. [`sizeAuto`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.automaticallysizeformfield?view=openxml-2.12.3) follows field-character formatting; explicit size uses half-points, within 1–200 points. Color and bold line weight follow the qualified style.

Form metadata is limited to `name`, `enabled`, `calcOnExit` and `checkBox`. It executes no calculations or interaction. Macros, bindings, unknown metadata, invalid states, conflicting sizes, decoration, navigation and forced complex-script checkbox fields remain unsupported.

## Verification and Playground

Regressions cover stale caches, both checkbox states, sizes, boundaries and malformed/active constructs. A4/Letter provider tests exercise body/header properties, SET/REF, checkboxes, page destinations and cleanup. Pixel comparisons use inline bookmark boundaries and different stale caches as a control. Standard PDF and PDF/A-1b/2b/3b are independently validated. Existing golden files remain unchanged; this does not claim universal Word-field fidelity.

Two synthetic documents with explicit paragraph spacing were also exported with Microsoft Word and compared per page against the exact same source converted through the real HTTP route. All four pages retain identical normalized text. Maximum text baseline differences are 0.18 points (A4) and 0.16 points (Letter); text immediately following an automatic legacy checkbox differs horizontally by up to 1.72 points. The legacy vector glyph and its spacing are approximations, not pixel-identical Word glyphs. Receipts and comparisons belong under the ignored `build/228-word-compatibility` evaluation directory, not existing golden resources.

Playground cleanup uses the same reader and can retain qualified fields/bookmarks. No permissive mode is added. The original corpus and detailed reports stay local; first-blocker movement is reported separately from complete conversion and visual fidelity.

## Ordered table bookmark endings (source #253)

Unreleased #253 admits qualified `w:bookmarkEnd` markers directly in a table before, between or after rows. Existing paragraph starts and story-local IDs remain authoritative. The reader consumes closures in document order, retains destinations and cell content, and does not insert layout paragraphs. Original table-ending ranges are checked before DOCX serialization can discard invalid attributes or marker text, retaining precise development source paths. Missing, unmatched, duplicate, malformed or foreign range markup remains an error; all existing archive, node, bookmark and cancellation limits continue to apply.

Text REF to these block-ended ranges remains explicitly unsupported through the existing paragraph-aware reference guard. The reader does not approximate structured table ranges as concatenated text. Unrelated qualified paragraph references continue to work. No public model, package, dependency or permissive option is added. The Playground shares source diagnostics and qualification; accepted cleanup retains original template bytes.

Synthetic A4/Letter references under `table-bookmark-ends` place three closures before, between and after table rows. Their independent Word exports are pixel-identical to controls whose closures occur in paragraphs. Twelve measured markers retain their page, physical face, color and point size, with maximum differences of 0.025 pt horizontally and 0.046 pt vertically against the existing control-engine path. Eight control-profile pages match two newly reviewed immutable golden images at 96 dpi without masks. Existing goldens are not replaced.

Regression coverage checks geometry, text, destinations, original/cleanup bytes, ordinary paragraph references and strict rejection of future-before-start marker order or affected text REF. Rebuild using `python tools/pdf-evaluation/own-engine/build_table_bookmark_fixture.py --base nuaforma-docx-pdf/src/test/resources --output build/table-bookmark-sources`; it writes `control/{a4,letter}` and `interleaved/{a4,letter}` sources. Export independently with Word and the exact fixture fonts; tests never generate references. Run `./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.NativeTableBookmarkEndsTest'` and the normal full build.

Microsoft documents matching bookmark IDs and includes tables among the permitted [BookmarkEnd parents](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.bookmarkend?view=openxml-3.0.1). NuaForma's bounded qualification above remains distinct from complete support for every bookmark position or structured text-reference result.
