# Word editor and web-export metadata (source #227 / #229)

This source change is not part of published `0.4.0-alpha.7`. Public APIs, dependencies, output profiles and renderer selection are unchanged.

## Header/footer shape identifiers

Word's [`hdrShapeDefaults`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.headershapedefaults?view=openxml-3.0.1) can contain defaults that affect existing VML objects. It is therefore not a generally ignorable element. The native reader now distinguishes identifier-allocation metadata from visible defaults instead of rejecting every declaration whenever any VML occurs in the package.

The qualified subset contains at most one Office `shapedefaults` and one `shapelayout`, with at most one nested `idmap`. Only an optional VML `ext="edit"`, unqualified nonnegative `spidmax` and comma-separated `data` identifiers are allowed. Identifiers are bounded to signed 32-bit values and maps to 64 distinct identifiers. Other attributes, foreign namespaces, duplicate declarations, text, malformed hierarchy and visible fill/stroke/shadow/default properties remain explicit failures. The root may also be empty.

These identifiers do not supply geometry, text style or rendering properties. They can coexist with otherwise qualified VML frames or lines; those objects still pass their own normal structural and layout checks. Nothing is added to the print model from the identifier declarations, and the original DOCX metadata is preserved. Supporting identifier metadata does not imply general VML or visible shape-default support.

## HTML-export preferences

[`relyOnVML`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.relyonvml?view=openxml-3.0.1) selects an HTML-export representation. It does not choose a PDF backend or turn off VML validation. The native reader accepts its standard on/off values (including an absent value) as nonprinting metadata.

`targetScreenSz` is likewise a web-export preference. Only the eleven [defined screen-size values](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.targetscreensizevalues?view=openxml-3.0.1) are accepted, with an explicit `w:val`. They never change section paper size, physical margins, image dimensions or pagination. Invalid values, duplicate new preferences, unexpected attributes, foreign namespaces, text and nested objects are rejected.

The existing HTML round-trip metadata restrictions still apply, including rejection of unsupported effective document bindings. No URLs, alternate images, external templates or browser resources are fetched.

## Save-time privacy and drawing-grid preferences (source #229)

Validated `removePersonalInformation` and [`removeDateAndTime`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.settings.removedateandtime?view=openxml-3.0.1) are save-time Word privacy preferences. They do not change PDF layout. Accepting these settings is not an anonymization service: the original DOCX and its authoring metadata are retained. The reader accepts standard on/off values and rejects unexpected attributes, nested content and duplicates.

The native reader also accepts `drawingGridHorizontalSpacing`, `drawingGridVerticalSpacing`, `drawingGridHorizontalOrigin`, `drawingGridVerticalOrigin`, `displayHorizontalDrawingGridEvery`, `displayVerticalDrawingGridEvery` and `doNotUseMarginsForDrawingGridOrigin` as editor drawing-grid preferences. Numeric values must be explicit nonnegative integer twips/counts within signed 32-bit range; spacing must be positive. Zero display frequency and origin values are supported. These preferences do not relocate existing anchored objects, change page geometry or activate text-grid layout. Effective `snapToGrid`, document grids and compatibility settings still pass their separate qualification.

Only the internal print-model copy loses this metadata. Normal DOCX generation and cleanup preserve the original qualified declarations. A4/Letter pixel comparisons with existing header/footer objects verify that these settings leave output unchanged in all four supported PDF profiles.

## Verification and Playground

Synthetic tests exercise the observed identifier structures, supported scalars and malformed/active variations. A two-page A4/Letter fixture places real VML frames and PAGE fields in both header and footer. Pages generated with the passive settings are compared with the same document without them, with zero allowed changed pixels. Standard PDF and PDF/A-1b/2b/3b are independently validated. Existing golden resources are preserved.

The same reader serves normal PDF generation and Playground cleanup. A supported template can retain these settings and VML objects without cleanup removals; existing DOCX serialization keeps the metadata. Unsupported visible defaults remain normal failures/removals under the existing caller-reviewed cleanup policy. No UI switch or permissive mode is added.

The 100-document external corpus and results remain local. Corpus tests report first-failure movement separately from successful complete conversion and Word fidelity.
