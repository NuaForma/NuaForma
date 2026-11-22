# NuaForma DOCX/PDF provider

Numbered paragraphs can retain inline page boundaries and their list numbering. See [Word controls and limits](../docs/list-page-breaks.md).

The development candidate maps qualified Word table alignment into the shared model after width negotiation. See [candidate semantics and limits](../docs/table-alignment.md).

The development candidate admits inline tabs inside list paragraphs. See [candidate semantics and limits](../docs/list-tabs.md).

The development candidate admits bounded extent metadata on non-wrapping objects and validates unrotated Office frame flags and opaque solid paint. See [candidate semantics and limits](../docs/unwrapped-drawing-extents.md).

The development candidate maps Word floating drawing order and bounded editing metadata into shared physical layers. See [candidate semantics and limits](../docs/floating-drawing-order.md).

Current source follows ordinary Word text-edge whitespace policies and maps qualified inline picture extents to shared padding. See [semantics and limits](../docs/whitespace-and-image-padding.md).

Current source supports bounded `w:w` text scaling and explicit body page breaks. See [qualified semantics and limitations](../docs/scaling-and-page-breaks.md).

Current source supports solid paragraph shading and tight automatic line spacing, including Word-qualified inheritance and pagination. See [semantics and qualification](../docs/paragraph-formatting.md).

Current source supports text highlighting and solid run shading, including inherited colors, theme fills and Word-qualified automatic foreground choices. See [supported semantics and limits](../docs/text-backgrounds.md).

Current source follows Word when data expansion causes right, center or decimal tab alignment to collide with preceding text. See [tab placement and Word controls](../docs/tab-collisions.md).

Current source supports qualified DOCX soft line breaks without creating extra paragraphs or list items. See [supported controls and Word comparisons](../docs/soft-line-breaks.md); this refinement is not in unchanged alpha.3 artifacts.

Source #295 distinguishes virtual exact-leading measurements from final physical-page ink bounds. See [scope and Word controls](../docs/exact-leading-ink.md); this refinement is not in unchanged alpha.3 artifacts.

Unreleased #283–#287 and the #279 follow-up qualify [print preferences and contained nested indentation](../docs/word-print-preferences-and-contained-tables.md), including the approved additive right-margin option in the shared model and no new dependencies.

Unreleased source #279–#282 adds bounded signed table indentation, historical grids and Word-backed numbering inheritance. See [scope, restrictions and verification](../docs/word-table-and-numbering-inheritance.md).

Unreleased source #273–#278 adds qualified print-source metadata, default horizontal flow and fractional layout spellings. See [scope and verification](../docs/word-review-and-numeric-spellings.md).

Unreleased source #268–#271 resolves qualified cell margins and inactive run properties, preserves legal first-row fragments and admits qualified final-line tabs. See [Word mapping and qualification](../docs/word-cell-margins-and-final-tabs.md).

Unreleased source #259/#265/#266/#267 admits qualified Word width spellings, theme cell fills and run character spacing, and distinguishes omitted from explicit table indents. See [width spelling and placement](../docs/preferred-table-widths.md), [theme fills](../docs/theme-cell-fills.md) and [character spacing](../docs/character-spacing.md).

Source #264 accepts qualified direct minimum row heights, including omitted-rule Word declarations. See [source mapping, pagination and Word comparisons](../docs/minimum-table-row-heights.md). Exact clipping heights remain unsupported.

Source #262 accepts qualified image compression-state hints while preserving source attributes, image bytes and existing drawing bounds. See [the source checks and Word controls](../docs/image-compression-states.md).

Source #258 passes qualified private-use symbols to the shared engine with the supplied physical font. See [glyph bounds and Word-control provenance](../docs/private-font-glyphs.md).

Source #260 resolves declared default table styles and inherited margin edges, with bounded ordinary border metadata. See [the table-style profile](../docs/word-table-style-defaults.md).

Source #259 translates qualified automatic and implicit table layout with physical/percentage preferences into the [shared preferred-width model](../docs/preferred-table-widths.md). This is unreleased source behavior.

Source #234 qualifies [Word-compatible synthetic bold](../docs/word-synthetic-bold.md) using the same supplied regular/italic face when the actual bold face is absent. Real bold faces take priority; missing families/italic faces and embedding restrictions still reject. No ambient font lookup or cross-family substitution is added. This is unreleased source behavior.

Source #233 qualifies [passive Office package resources](../docs/word-passive-office-package.md): bounded property-panel metadata, equivalent auxiliary styles and owned unused font/theme resources. Caller fonts remain mandatory; active bindings and divergent effects reject. The Playground shares this admission and focused cleanup. This is unreleased source behavior.

Source #232 qualifies [Word typography preferences](../docs/word-typography-preferences.md): trailing-space underlining, effective punctuation containment and hyphenation suppression, simple-script Far East preferences, style/grammar editor metadata and bounded standard-contextual ligatures. Active unqualified layout remains an explicit error. The Playground uses the same provider and preserves qualified constructs during cleanup. This is unreleased source behavior.

Source #230 qualifies validated style-pane sort preferences, next-paragraph style metadata and bounded Chicago note numbering with ordinary automatic separator spacing. See the [editor preferences](../docs/word-editor-settings.md#style-editing-preferences-source-230) and [note profile](../tools/pdf-evaluation/own-engine/NOTES.md#chicago-numbering-and-automatic-separators-source-230). This is unreleased source behavior; existing paragraph styles and strict unsupported-feature failures remain.

Source #231 adds [qualified table lists, deeper numeric starts, bounded level overrides, minimum line spacing and one-section note restart preferences](../docs/native-list-spacing-compatibility.md). The public PDF paragraph API gains an optional immutable line-height floor without changing existing constructor/copy descriptors. The Playground uses the same provider and retains these constructs during cleanup; no UI, transport or publication change is included.

Source #229 additionally qualifies bounded multi-paragraph DOCPROPERTY values and passive save-time privacy/drawing-grid preferences. See the [field profile](../docs/word-fields-and-bookmarks.md#multi-paragraph-properties-source-229) and [editor-setting profile](../docs/word-editor-settings.md#save-time-privacy-and-drawing-grid-preferences-source-229). This is unreleased source behavior.

Source #228 qualifies body-level bookmark boundaries, actual string document properties, literal SET/REF values and passive legacy form checkboxes. See [the field and bookmark profile](../docs/word-fields-and-bookmarks.md); this is not part of published alpha.7.

Source #149 supports bounded inline vector checkboxes through the
same native reader and shared PDF engine. See the [source feature and contract
scope](../docs/checkbox-content.md); this is not part of published alpha.7.

Version 0.4.0-alpha.5 (#208) retains validated style revision metadata and identifies rejected image elements/attributes at their concrete source locations. Playground cleanup can remove the focused construct while retaining a supported logo and surrounding story; normal generation remains strict. See [scope and review requirements](../docs/template-cleanup.md#style-revisions-and-focused-drawing-failures-040-alpha5-208).

Version 0.4.0-alpha.4 (#203) preserves qualified unwrapped floating images and existing VML/DrawingML text frames in page bands, zero-valued Word relative-size placeholders, and nonprinting paragraph-mark highlighting. Unwrapped objects preserve their physical coordinates without moving ordinary footer text or reserving extra body space. Unsupported anchor children identify their own XML location, so cleanup can retain the rest of the story. See the [floating-story qualification](../tools/pdf-evaluation/own-engine/WORD_FLOATING_BANDS.md).

Version 0.4.0-alpha.3 Word compatibility work for #196 is described in the [paragraph, logo and page-band profile](../tools/pdf-evaluation/own-engine/WORD_COMPATIBILITY.md): signed physical-page indents, paragraph-mark metrics, measured header/footer reservation and a bounded EMF subset. Synthetic A4/Letter cases contain header/footer logos and real footnotes; the private original document has not been qualified.

The registered `DocxPdfProvider` reuses `nuaforma-docx` for template processing and DOCX output, translates supported filled DOCX into the neutral model, and writes PDF through `nuaforma-pdf`. Rendering takes place in the hosting JVM with request-local state. There is no LibreOffice backend or external document-renderer fallback. The native engine is published in the `0.3.0-alpha.2` evaluation release.

Source #226 adds bounded embedded JPEG decoding and validated sensitivity-label package metadata. See [qualified formats, limits and label-policy boundaries](../docs/native-picture-compatibility.md). This is not part of published alpha.7.

Source #227 also qualifies identifier-only `hdrShapeDefaults` with supported VML content and the HTML-export preferences `relyOnVML`/`targetScreenSz`. These do not alter PDF geometry or renderer selection; see [validation and remaining limits](../docs/word-editor-settings.md).

## Output and validation

Standard PDF 1.7 and PDF/A-1b, 2b and 3b use the existing profile identifiers and version rules. The absent profile remains `pdfa-3b`. PDF/A-1b rejects transparent images; there is no silent flattening or profile downgrade. Selected standalone TrueType faces are embedded subject to their permissions. Use exact family/face resources or the configured deployment catalog; no system-font substitution is performed.

Every delivered native PDF requires independent structural/font/active-content checks. Archival output additionally uses the exact requested veraPDF flavour unless the trusted operator explicitly disables conformance checking. The existing immutable, default-on operator policy remains separate from request options. Disabling conformance checking retains structural checks and does not establish PDF/A compliance. PDF/UA, PDF/A level A and attachments remain unsupported.

The separate validator uses Java 17. Container validation retains a private directory, bounded JVM/process, read-only installations, a cleared inherited environment and cleanup. Server and validator share the ordinary container filesystem and network boundary under runtime default policies; Bubblewrap and a custom seccomp file are not required. Deployment-level egress controls remain the operator's responsibility. An unavailable or failed validator never causes unvalidated delivery or a renderer fallback. Standard DOCX output remains available without the PDF validator.

## Supported source profile

Version `0.3.0-alpha.2` detaches validated passive Word template references during the shared DOCX preprocessing step, before loading or rendering. It uses only saved internal styles, never the referenced `.dotx`/`.dotm` or a local/network target. Absent or explicitly disabled automatic style updates are supported; enabled updates and malformed references reject with actionable diagnostics. See the [DOCX policy](../nuaforma-docx/README.md#input-and-failure-policy) for output handling. Earlier versions reject these references. This improvement does not broaden support for other external resources or visible constructs.

The native profile is deliberately bounded to the qualified shared layout features: explicit page geometry and fonts, supported inherited paragraph/run/table styles, multilevel lists, tabs, next-page sections, page-band variants and decimal fields, standalone inline PNG/JPEG-image paragraphs, supported frames/rotations, and qualified fixed/merged/nested/fragmented tables. Roadmap part 10 adds paragraph-bound DrawingML PNG images, rectangular text wrapping and source cropping; [qualification](../tools/pdf-evaluation/own-engine/ANCHORED_IMAGES.md) records its current limits. Word compatibility mode 12 (including the absent/default setting) retains legacy table-text alignment; unreleased [source #244](../docs/word-compatibility-modes.md) qualifies mode 14 with its legacy policy. Mode 15 anchors the outer table border to the declared indent. Declared table widths must still fit their containing page; conversion does not shrink columns to hide overflow. Language support is per qualified script/font combination; this is not universal Word compatibility. PNG/JPEG image pipes reuse existing DOCX image normalization before native conversion, within standalone image paragraphs. Mixed inline text/image paragraphs remain explicitly unqualified.

Explicit hyperlinks and matched bookmark ranges support inert HTTP/HTTPS/mailto actions, internal named targets and flat user-bookmark outlines. Wrapped links and links inside qualified tables retain their text style, including single underlining. Missing/duplicate targets and unsafe URI schemes reject; conversion never opens or downloads a link. See the [navigation profile](../tools/pdf-evaluation/own-engine/NAVIGATION.md) for source and layout restrictions.

The [contents/reference profile](../tools/pdf-evaluation/own-engine/REFERENCES.md) adds bounded decimal SEQ counters, REF/PAGEREF text/page references, body page totals and a heading-based clickable TOC. Simple and complete complex Word fields are translated into symbolic neutral-model values; saved field results supply formatting only. Generated contents retain explicit TOC paragraph styles and logical heading/caption/reference roles. Cross-paragraph text ranges, general field expressions, custom TOC selectors and general content controls remain unqualified. Preparation for future PDF/A level A does not change the available output profiles.

The [footnote/endnote profile](../tools/pdf-evaluation/own-engine/NOTES.md) maps source note parts, continuous numbering and superscript markers into the shared model. Footnotes reserve page-bottom space and may continue; endnotes follow the main text. The qualified profile uses one paginated section, supported paragraph text and ordinary separator geometry. Source #231 accepts `eachSect` within that one section; custom marks, each-page or multi-section note-number restarts, wrapped objects and splitting table rows with notes remain unqualified. The independent Word record explicitly identifies a one-line continuation-boundary difference; no universal Word-pagination claim is made.

Word authoring metadata is interpreted separately from print properties. Known editor identifiers, style-list metadata and inactive script fallback metadata may be ignored; unsupported effective settings, remote resource loading, revisions, multiple columns, unqualified fields/anchoring and other visible constructs reject explicitly. Production-adapter source budgets include 8 MiB compressed/expanded DOCX, 4 MiB per part, 256 ZIP entries, 32 pages and the shared model's bounded content limits; stricter caller limits still apply. The entry ceiling increase is unreleased source work under #297; all byte limits remain unchanged.

Exact line boxes that would clip the supplied font bounds reject explicitly. The native smoke test qualifies 11-point Liberation Serif at a 16-point exact line; its former 13.2-point line remains a negative case. PDF version checks use the effective document/catalog version, together with independent structural and archival validation, rather than the header line alone.

Requested text watermarks use the existing `TextWatermark` options in standard PDF, PDF/A-2b and PDF/A-3b: text resolves once through the same bounded DOCX template session, then the native engine centers a background decoration on every physical page. RGB color, clockwise rotation and opacity are retained; decoration does not participate in pagination. Every requested watermark rejects for PDF/A-1b, including opacity 1 or text that would resolve empty. Blank resolved text suppresses the watermark in the other profiles. Tabs and line endings normalize to spaces, matching the existing DOCX shape representation. Fonts come from the explicit document-default family and the supplied deployment/request catalog; there is no ambient font substitution. The qualified script restrictions still apply; rotated RTL glyph extraction may follow physical order, while `ActualText` retains the original logical Unicode.

`paragraphDefaults` fallbacks are not yet qualified for the native route and reject explicitly. Embedded source-font packages and arbitrary Word-saved documents are not universally supported. These are remaining compatibility gaps, not permissions to omit styling.

## Embedded configuration

`DocxPdfProvider(NativePdfConfiguration(...))` provides explicit embedded composition; `NativeDocxPdfProvider` exposes the same native implementation for direct use. `NativePdfFont` validates font identity and holds an immutable byte snapshot. Supply an independent `PdfProcessValidator` with the reviewed Java 17 distribution. The configuration bounds concurrent conversions with immediate admission failure rather than an unbounded queue. Each call checks the hosting cancellation/deadline and limits PDF output before publishing bytes to the caller.

The default registered provider captures trusted JVM properties and deployment-font snapshots at construction. The container sets `nuaforma.pdf.validator.java`, `nuaforma.pdf.validator.directory` and `nuaforma.pdf.validator.launcher`; `nuaforma.pdf.parallelism` retains its bounded operator setting. Libraries do not read environment variables. `nuaforma.pdf.pdfaValidation` retains the existing strict `true`/`false`, default-on policy. The obsolete renderer launcher does not select another backend.

Explicit embedded composition uses `NativePdfConfiguration`; zero-argument discovery and content/value-pipe overloads capture the same native configuration. Default profile identifiers remain stable. Qualify actual templates against the supported source subset before adopting this evaluation version.

## Operator conformance policy (unreleased issue #93 source)

Only exact `true` or `false` is accepted for the trusted `nuaforma.pdf.pdfaValidation` property; the absent value enables it. The common server/Playground entry point maps `NUAFORMA_PDFA_VALIDATION` to this immutable startup policy. Requests cannot override it. Disabled conformance retains structural/font/active-content validation and never claims verified archival compliance. All validator dependencies and their notices remain bundled.

## Qualification and references

Selected alpha.6 (#172) adds [paragraph borders and passive drawn lines](../tools/pdf-evaluation/own-engine/LINES.md), including inherited automatic paragraph rules, connected frames and opaque VML/DrawingML strokes in body/table/page-band paragraphs. The guide defines the qualified styles and positioning limits. This does not add arbitrary shape, wrapping-line or theme-effect support; earlier published alphas remain unchanged.

The engine's reviewed golden files and Word-export comparison documents remain protected references. Native adapter tests additionally exercise template expansion, a Word-saved DOCX, branding/fonts, page layout, actual independent validation, limits, concurrency and recovery. LibreOffice-specific orchestration helpers have moved out of production sources into test-only historical comparisons; their tests do not qualify the native backend.

The [registered-provider integration trials](../tools/pdf-evaluation/own-engine/NATIVE-INTEGRATION.md) retain six new Word-matched pages, including an 80-row conditional report, with per-page content/geometry results and visual review.

Full repository/consumer, Word/golden, REST/Playground and native AMD64/ARM64 checks qualify the bounded source profile. Each release still requires exact-candidate validation, signing, source/notices and website handoff. See the [roadmap](../docs/architecture/own-engine-roadmap.md) and [shared engine guide](../nuaforma-pdf/README.md).

### Hidden template text (source fix #154)

The DOCX stage evaluates only print-view text and removes resolved hidden runs from its private PDF intermediary before automatic font collection and native reading. Hidden text does not enter the PDF, and fonts used only by removed hidden runs are not required; ordinary DOCX output retains the source runs and their formatting. This also applies to development diagnostic PDFs. Visibility uses the [Word template rules](../nuaforma-docx/README.md#word-template-rules), including inherited toggles and explicit overrides; it does not infer visibility from color, position or the operatorâ€™s Word display settings. Existing golden files remain unchanged. This change is included in the selected alpha.5 release; earlier artifacts remain immutable.

## Rich-text source feature

The selected alpha.5 release accepts the same filled [`richText("rtf")`](../docs/rich-text.md) document for standard PDF and PDF/A-1b/2b/3b, including single strikeout. Native layout/script restrictions, font embedding and independent validation remain mandatory. The owning guide explains scope and limits; earlier alphas do not contain this pipe.

## Passive bibliography metadata

Current source qualifies bibliography `Sources` parts reached through document `customXml` relationships, with optional validated datastore properties. The conversion copy excludes only these identified passive metadata trees and relationships from its print model; the owning DOCX generator retains them. Bibliography text cannot change text direction, template evaluation or printed content. Bibliography content remains strictly validated. Source #250 separately qualifies [bounded unbound custom XML data](../docs/word-passive-office-package.md#unbound-custom-xml-data-source-250); data bindings, orphan parts in the conversion copy, duplicate IDs, invalid owned graphs and external property targets reject explicitly. Before conversion, the owning DOCX serializer omits unreachable ZIP members; this does not admit arbitrary active or malformed custom XML. Existing archive/XML/deadline limits remain. Citation fields and arbitrary custom-XML data binding are not added by this profile.

## Playground template cleanup (source #191)

Selected 0.4.0-alpha.2 (#191) adds the optional contract 0.9 template-cleanup facet for the development Playground. It removes unsupported structures from a separate DOCX copy without evaluating template instructions, validates the retained source through the existing native reader and non-evaluating DOCX facade, and returns a separate removal report. The caller alone accepts content/appearance. Normal generation remains strict and published 0.4.0-alpha.1 artifacts remain unchanged. See [template cleanup](../docs/template-cleanup.md).

## Passive package properties (0.4.0-alpha.1, #182)

The native reader accepts validated scalar custom document properties in `docProps/custom.xml`, linked by an internal package relationship and the matching content type. Names and values remain nonprinting metadata unless explicitly referenced by a qualified `DOCPROPERTY` field; braces, including unbalanced braces, never activate the template parser. String, boolean, numeric and timestamp values are qualified as passive metadata; nested values, binary/active constructs, linked properties, duplicate identifiers, malformed relationships and unknown types reject explicitly. Source #228 adds bounded actual string-property evaluation through the [field profile](../docs/word-fields-and-bookmarks.md); unsupported visible fields/types still reject rather than using potentially stale cached text.

The DOCX stage already validates and detaches passive attached-template references without downloading their targets. The PDF reader accepts the resulting empty `word/_rels/settings.xml.rels` when the settings part exists. Nonempty settings relationships remain unsupported. The settings themselves are retained for the existing print-property interpretation; this feature does not discard effective settings or admit unqualified print settings. Unsupported ZIP members identify their bounded package path through the existing diagnostic `part`, including in Playground development diagnostics. Existing limits and external-resource restrictions remain in force. No new request option, public contract or Playground control is required; earlier releases remain unchanged.

The same source fix qualifies known editing-only settings: document editing protection, style-pane filtering, system-font save preferences, subdocument statistics and chart-change tracking. Source #227 accepts validated identifier-only header shape defaults alongside otherwise supported VML objects; visible defaults remain unqualified. See the [editor/web-setting profile](../docs/word-editor-settings.md). Print properties such as default tab intervals, decimal separators and note settings continue through the existing reader. Protection metadata does not encrypt a document or grant PDF permissions; see [Microsoft's editing-restriction definition](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.documentprotection).

Section `docGrid` declarations with an absent or `default` type are inactive and may be excluded after structural validation. Active line/character grids, duplicate declarations and malformed pitches reject explicitly; see [the grid modes](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.docgridvalues). This does not add East Asian grid layout support.

A nonnegative printer paper `pgSz/@code` is accepted as printer-selection metadata; explicit page width, height and orientation still determine PDF geometry. This does not require a printer or alter the requested physical page size.

A header/footer without printed content may be omitted when its saved distance leaves no usable band inside the margin. This applies only to plain blank paragraphs without borders, line decorations, anchored images, lists, fields, destinations or outline roles. Visible content and decorated whitespace still require valid band geometry; they are never dropped to make conversion succeed. Bands with valid geometry retain their existing behavior.

Numbering definitions may retain validated list-gallery template codes, tentative-level flags and positive decimal durable instance identifiers. Those authoring attributes are excluded without changing the existing list rules. Explicitly disabled `restartNumberingAfterBreak` is accepted; enabled section-dependent numbering restarts remain unsupported. Actual numbering formats, levels, counters, style links and printed formatting still pass the existing strict reader.

Known paragraph revision/collaboration identifiers are removed from the print copy, including those retained on note separators. Unused complex-script size/bold/italic properties are excluded only when the existing document-wide script check finds no complex text or forced complex-script use; malformed fallback properties still reject. Actual complex-script formatting keeps the existing strict checks.

Selected 0.4.0-alpha.1 includes #184 and supports automatic before/after spacing and its HTML compatibility policy, physical-page mirrored indents, conditional table-style regions and legacy Normal-style precedence exceptions. Explicit kerning thresholds and standard OpenType ligatures use caller-supplied font outlines while retaining logical Unicode text and subset/full embedding. The [formatting proof](../tools/pdf-evaluation/own-engine/FORMATTING.md) owns the independent Word fixtures, compatibility rules and bounded combinations; these changes are selected for 0.4.0-alpha.1; earlier releases remain unchanged. Mirrored lists/tabs/RTL, positional conditional styles on merged cells, explicit `cnfStyle` and broader OpenType features remain unsupported. Unknown or malformed settings still reject explicitly.

Bounded `webSettings` HTML round-trip metadata (division margins/borders and browser/PNG preferences) is validated separately and excluded from the print model. Paragraph `divId` bindings remain unsupported. These are [stored HTML structures](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.div), not additional Word paragraph borders. Foreign elements, external relationship attributes, unexpected nested content and arbitrary text reject with the actual web-settings part; a web-settings rejection no longer points at the font table.

Selected alpha.6 (#170) preserves concrete native rejection details and source coordinates in development mode. The optional contract 0.8 `DocumentDiagnosticProvider` produces marked DOCX inspection copies even when native PDF conversion fails; a separate conservative preview leaves malformed fragments and structural regions literal. See the [diagnostic guide](../docs/template-diagnostic-pdf.md#source-diagnostics-and-docx-inspection-unreleased-170). Earlier published alphas remain unchanged.

Selected for 0.4.0-alpha.6, #213 qualifies passive Word picture metadata and corrects EMF header classification, with the cleanup download beside its control in Playground. See [qualified scope and regression coverage](../docs/word-picture-metadata.md).

Unreleased #223 qualifies unused solid/null extended-pen metadata and valid BS_NULL/PS_NULL combinations. Actual pattern/bitmap failures identify the relevant BrushStyle, cbBmi or cbBits instead of one generic pattern-pen message. See [qualified pen semantics and remaining limits](../docs/word-picture-metadata.md#solid-and-null-pen-metadata-unreleased-223). No new dependency, font or published release is introduced.

Source #235 qualifies inherited/direct `w:jc=both`, numbering cleanup bookkeeping, internal JPEG/PNG package previews and valid Word font-save preferences. These passive preferences do not change supplied-font or PDF embedding policy. See [qualification and limits](../docs/word-justification-metadata.md).

Unreleased #236 qualifies valid `autoSpaceDE`/`autoSpaceDN` switches for unaffected text and explicitly disabled settings, with field-aware guards for active East Asian spacing. See [qualification, references and limits](../docs/word-automatic-text-spacing.md). The shared PDF API and font policy are unchanged.

Valid `adjustRightInd` paragraph switches are qualified within the existing absent/default-grid profile; active document grids remain unsupported. Source/cleanup bytes and right-indent geometry are retained. See [automatic right-indent adjustment](../docs/word-right-indent-adjustment.md).

Paragraph text baselines, bounded automatic alignment, omitted Latin run-language inheritance and validated alternate style display names are qualified in source. Source/cleanup bytes remain intact; see [paragraph metadata](../docs/word-paragraph-metadata.md) for script boundaries, references and regressions.

Qualified `mc:AlternateContent` is selected before PDF template evaluation and native reading, including development inspection. Namespace bindings determine the selected representation; unsupported selected geometry remains rejected. Supported cleanup preserves source bytes. See [conditional Word objects](../docs/word-markup-compatibility.md).

Unreleased #241 supports qualified mixed font sizes in automatically spaced paragraphs using each physical line's actual font metrics. Minimum line heights, fragmentation and supported container layouts share those measurements; [mixed natural line spacing](../docs/mixed-natural-line-spacing.md) documents Word references, preserved goldens and remaining bidi/floating-image boundaries.

Unreleased #245 resolves [qualified Word run theme colors](../docs/word-theme-colors.md), including embedded palettes, bounded HSL tint/shade and inherited/direct color overrides. Original themed-color structure is checked before serialization; unsupported effects remain explicit. Standard PDF and all three B-level archival profiles share this mapping.

Unreleased #246 admits a [qualified initial continuous section and passive form-editing protection](../docs/word-initial-section-metadata.md). Explicit page geometry and genuine later transition restrictions remain enforced; qualified cleanup retains original bytes and protection settings. No shared model or public API is added.

Unreleased #247 also admits standard, untyped [OOXML trash items](../docs/word-package-trash.md) as bounded physical ZIP data. Their bytes do not participate in PDF layout or template processing; all ordinary package and relationship checks remain active.

Source #248 qualifies bounded [mixed Latin font roles](../docs/word-mixed-latin-fonts.md), preserving independent ASCII/high-ANSI inheritance and actual supplied families through the existing neutral text model. Source #249 validates [passive picture and revision-recording preferences](../docs/word-passive-editing-preferences.md) without accepting tracked content or changing image bytes. The Playground shares both profiles and the original-source diagnostics. These changes are unreleased source behavior.

Source #251 qualifies [bounded unused document-variable storage](../docs/word-passive-editing-preferences.md#unused-document-variable-storage-source-251). Values remain in the template without becoming print text or instructions. Original metadata errors retain development locations; `DOCVARIABLE` field rendering is not added. This behavior is unreleased.

Source #252 qualifies [nonbreaking Unicode-space boundaries](../docs/word-unicode-spaces.md) without redundant preservation markers, with unchanged glyph/font rules and Word-backed wrapping tests. This is unreleased source behavior.

Source #253 qualifies [ordered bookmark endings in tables](../docs/word-fields-and-bookmarks.md#ordered-table-bookmark-endings-source-253), preserving row order and destinations. Text REF to affected block-ended ranges remains unsupported. This is unreleased source behavior.

### Additional unreleased source qualifications (#254–#257)

Validated [spelling/grammar display preferences](../docs/word-passive-editing-preferences.md#spelling-and-grammar-display-preferences-source-254), [printer tray codes](../docs/word-initial-section-metadata.md#printer-tray-selection-source-255) and [empty unused Word editing bookmarks](../docs/word-fields-and-bookmarks.md#empty-word-editing-bookmarks-source-256) are omitted only from the private print interpretation. Original template/qualified cleanup bytes remain intact. [Explicit default font hints](../docs/word-mixed-latin-fonts.md#explicit-default-font-hints-source-257) use the existing qualified font selector. Original malformed metadata, active references and unqualified font overrides retain their strict source checks. A4/Letter Word counterfactuals and all four PDF profiles reuse existing immutable goldens. The Playground shares the same provider behavior; these changes add no option, package, dependency or public model.

Unreleased implicit paragraph-style handling and its qualified limits are documented in [Word paragraph-style defaults](../docs/word-paragraph-style-defaults.md).

Unreleased regular legacy text inputs and unused Word editing selections are described in the [field and bookmark guide](../docs/word-fields-and-bookmarks.md), including remaining character and navigation limits.

Unreleased source work additionally qualifies [note separator metadata](../docs/word-note-separator-metadata.md) and [Word sequence field formatting](../docs/word-fields-and-bookmarks.md#sequence-field-formatting), preserving the documented source and rendering limits.

Unreleased source work also qualifies [case-insensitive Word sequence counters](../docs/word-fields-and-bookmarks.md#sequence-counter-identity) and [ordinary body-cell paragraph pagination defaults](../docs/word-cell-pagination-defaults.md), with retained original template bytes and strict source validation.

Selected 0.5.0-alpha.4 [wide fixed-table qualification](../docs/wide-fixed-tables.md) retains explicit body grids extending into the right margin while preserving physical page, nested-cell and page-band bounds.

Selected 0.5.0-alpha.4 [long-word wrapping](../docs/long-word-breaking.md) preserves fixed body-table columns while breaking oversized unshaped words at grapheme boundaries. Automatic widths, nested cells and page bands retain their existing policies.

Selected 0.5.0-alpha.4 [font family identity qualification](../docs/font-family-identities.md) is shared by DOCX and native PDF. Extended Light, Display and similar designs retain their precise family names rather than impersonating their base family; ordinary four-face style linking remains supported.

## Additional native layout support

Unrotated DrawingML text boxes can use Word shape autofit. Wrapped text adjusts the height while retaining the specified width. Unwrapped text adjusts both dimensions. The final shape and contents must fit on the physical page.

A narrow profile admits direction properties on ordinary nonprinting header tabs and paragraph marks when the ordinary and complex-script faces and sizes match. The check runs after template evaluation. Visible text still follows the native script and direction checks.

A qualified page-relative text box can align to the left, center or right and to the top, center or bottom of the physical page. Alignment uses the finished frame size. A stroke on an expressly aligned page edge follows the physical page crop.

Embedded Office SVG pictures may use an internal SVG part with static filled paths and a distinct embedded raster fallback. The renderer uses the SVG for qualified inputs with the existing picture placement. External references, scripts, complex styling and effects remain outside this profile.

In development mode, text-frame overflow diagnostics identify the affected dimension or physical edge and include the measured required and available space in points.

Fixed tables can retain empty grid positions at either outer edge of a row. The declared omission must agree with the physical column widths. Only actual cells paint their content and borders.

The Word numeric picture 0 is accepted on PAGE, NUMPAGES and SECTIONPAGES. These fields still resolve against the final pagination.

Ordinary unrotated fixed DrawingML text boxes in the main story can keep their original rectangle while longer text continues below it. The frame and text remain within the physical page.

In a merged table, permission for an ordinary row to split is accepted when the resolved table can keep the relevant row whole. The current restrictions for fragmenting merged cells still apply.

A qualified left-to-right paragraph can retain a bookmark at the end of its last physical line or at the start of an empty line. Links resolve to this anchor without adding printable characters.

Standalone text frames with omitted fill and outline declarations can retain their transparent paint. Explicit colors still follow the qualified frame paint profile.

Word paragraph styles can reset an inherited outline level to body text. Direct body-level overrides on heading paragraphs require additional qualification.

Generated contents require a style for each requested outline level used by a heading in the document. Unused requested levels can omit their styles.

Separately positioned page-fixed footer rectangles can retain Word's non-overlap flag when their physical boxes are disjoint. An explicit offset can start from the right margin.

Hyperlinks can retain the clickable area of tab runs, including the full gap for tabs without visible leaders. Existing navigation target validation applies.

Fixed body frames can share a page with footnotes when their painted bounds leave the actual footnote region clear. Intersections remain explicit layout errors.

Saved DrawingML outline properties can accompany an explicitly invisible frame outline. The stored properties are validated and the visible frame remains transparent.

A qualified Word page reference within its own internal hyperlink resolves to the actual page of the common target.

Fixed flat body tables with one solid color can use different qualified direct cell-border widths.

A background header picture can retain a closed tight wrapping contour that sits wholly above the body. Other kinds of shaped wrapping require separate qualification.

Qualified fixed table cells can suppress the end mark while retaining visible text and ordinary tab layout.

Existing external-link targets can remain clickable inside a fixed unrotated text frame. Annotations use translated physical page coordinates.

When a hidden ordinary cell mark follows a trailing soft line break, the final unprinted line is omitted while earlier lines remain intact.

Isolated fixed unrotated footer frames can continue visible text below the nominal rectangle. The actual ink remains within the page and clear of other footer objects.

Numbered paragraph marks can retain their highlight on the label and supported separator.

Validated absent or nil text-run borders leave normal text drawing unchanged.

Embedded pictures with an explicitly invisible outline can retain a validated miter and empty arrow ends.

Validated saved fill, line and effects metadata leaves a frame's explicit unpainted appearance unchanged.

A complete legacy checkbox field with no form metadata or saved result remains unprinted.

When a qualified plain typography property differs between style parts, Word's primary styles control the result. Valid primary-only style references and reciprocal character links are preserved.

Saving HTML as separate files is an authoring preference and leaves PDF output unchanged.

The preference to save a document preview image leaves PDF output unchanged.

Page-fixed unwrapped pictures can keep an external link whose clickable rectangle follows the picture.

Qualified horizontal borders of equal width can retain regional colors across cells and horizontal merges.

A bounded final EMF object deletion can immediately precede a valid end record when only that deletion falls outside the recorded count.

Fixed unwrapped page-band pictures may retain their visible portion when a physical page edge clips the source crop.

A terminal cell preference within the qualified downward rounding tolerance retains the saved fixed-table grid.

A fixed full-page header picture can layer behind body text while retaining the order of smaller header objects.

An isolated single-cell page-band table can keep its qualified top stroke and ordinary text.

A fixed unwrapped image in the first cell of an isolated one-row page-band table can retain its column and page offsets.

A qualified picture can keep tiny proportional discrepancy between its stored internal and outer extents. Fixed page-band table text can wrap an oversized word within its column.

A fixed left-aligned page-band table in current Word layout mode can extend into the saved right margin while staying within the page.

Qualified names can contain an ordinary colon or curly apostrophe. A closed same-paragraph bookmark can retain zero-valued column metadata.

An isolated qualified two-column automatic table can fall back to its saved grid when its styled contents need word wrapping.

A plain white source background without children retains the default physical page appearance.

Within a render, crops and semantic copies of a picture can share its bounded decoded raster.

The checked setting for tracking future moved text does not alter the existing print model.

Existing hyperlink relationship diagnostics report their source part and relationship position without displaying the destination value.

Dedicated fixed horizontal inline text frames can retain their visible rectangle and body-line metrics.

Square-wrapped ordinary pictures retain Word's foreground layering and saved drawing order.

The qualified inactive punctuation overflow preference is retained for ordinary text. Active East Asian punctuation geometry remains separate.

A leading explicit break keeps its physical page boundary before a supported paragraph with keep rules.

Explicitly positioned unwrapped pictures can fit their source aspect ratio into qualified page or margin percentages.

Qualified bookmark destinations before a leading hard break retain the source page, and destinations after it follow the text.

An ordinary paragraph after a standalone contents end marker retains its text, formatting and links.

Supported fixed, unwrapped rectangular frames resolve their relative width and height independently.

A centered or decimal-aligned field can move left to remain inside the line when the full field fits after the preceding text.

An unresolved ordinary Word hyperlink keeps its visible text. Existing names resolve with explicit case-preserving identity.

An in-bounds stop can move the field to a continuation line. A right-aligned outside stop can use the physical margin of ordinary body text.

Ordinary header and footer paragraphs can keep a qualified right tab at the page-margin boundary.

An unpainted last left tab retains its font metrics when the requested blank advance extends past the line.

Qualified horizontal bands retain ordinary row lines without adding an interior vertical stroke to an outside-only table outline.

Separately stored Word hyperlink fragments retain the complete external destination and visible label.

An ordinary row with no owning note can stay whole in note-aware layout. A required physical row split retains an explicit diagnostic.

Oversized visible HTTP and HTTPS paths can wrap at existing character boundaries while retaining styling and navigation.

A matching visible web hyperlink can continue at a character boundary when the next preferred boundary is wider than the line.

A literal SET assignment may retain its result value for ordinary references when Word wraps the saved invisible result in its own bookmark.

An ordinary title sharing the opening field-code paragraph stays in its original place when the saved contents start in the next paragraph.

Qualified percentage cell shading blends the stored RGB colors into Word's PDF shade.

A fixed Word grid may retain a subpoint column when its actual spanning cells all have room for their margins.

A fixed text-relative table with an isolated anchor stays at its saved vertical offset while the body continues independently.

A bookmark after a standalone Word page break keeps its destination on the source page.

A right-aligned line with explicit center and right tab stops preserves the tab fields and follows Word's right-edge alignment.

A fixed, flat modern Word table can retain independent border strokes and measured cell clearances, including when a saved grid supplies omitted cell widths.

A whole fixed row may continue on the next page when the adjoining strokes match the table's outside border.

A word moving to a narrower continuation line is measured against that line before applying the existing word-breaking policy.

Consecutive undecorated terminal tabs can occupy blank continuation lines when their default or ordinary left stops exceed the text area.

A row can split at ordinary line boundaries when its own sides and surrounding joins match the fixed table outline.

A visible run without a resolved font family reports the missing declaration at its source location.

Empty trailing fields after a nondecorated right tab also keep the blank continuation lines used by Word.

A Word Watermarks gallery wrapper can retain its ordinary saved header drawing, including an empty closing marker. The drawing itself still follows the native profile.

A saved table of contents keeps its document styles when Word stores separate font properties for insertion and the control end mark.

Modern body tables with complete explicit margins in every cell retain their spacing and borders when the table does not declare default cell margins.

Fixed nested tables with complete direct margins can keep their saved grid and alignment inside non-splitting parent rows.

Blank endnote separator policies can remain in documents that contain no numbered endnotes. Existing footnote layout is preserved.

Word paragraph shading retains the explicit solid color or measured percentage blend, including through style inheritance.

Word run shading uses its own measured solid or percentage color while retaining paragraph backgrounds and text styling.

An empty footnote or endnote continuation notice with explicit zero paragraph spacing leaves note pagination intact.

A valid bookmark ending before a cell's first paragraph keeps its original text boundary without adding a paragraph.

Validated internal PNG definitions for unused Word picture bullets leave printed numbering unchanged. Visible picture bullets remain outside the qualified profile.

Modern fixed and automatic flat tables can retain distinct border colors when the strokes share one width.

An isolated nonoverlapping table with a qualified text-relative anchor can keep ordinary full-width flow with a 100% automatic width.

An explicit inline cell paragraph can cancel a saved floating style frame and its overlapping behavior while preserving the cell's text.

Fixed square-wrapped body frames that cover the text column retain their qualified bottom effect extent and the following text clearance.

Solid native table strokes can be up to six points. Modern flat tables with one border color retain mixed stroke widths, including with automatic layout.

A qualified full-width two-row merge preserves the blank continuation line and thicker hidden top-rule clearance without painting a divider.

Automatic and percentage cell widths preserve a qualified unbroken line when Word disables wrapping for that cell.

A hidden, undecorated empty cell marker contributes neither its final line nor its paragraph spacing.

Single underlines preserve explicit RGB independently of run ink, while automatic color follows that ink.

An isolated fixed drawing frame in the first header paragraph uses the band origin before the paragraph spacing.

Thick single underlines apply Word-verified line metrics within the measured 8–20 point range.
