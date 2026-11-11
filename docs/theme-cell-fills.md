# Theme-based table cell fills (#265)

Unreleased source support resolves direct and inherited table-cell background theme roles, including qualified tint/shade transforms. A theme value takes priority over a stale cached fill. `themeFill="none"` yields no theme fill, matching the independent Word probe even when a cached RGB value is present. Existing run-color semantics remain separate. The adapter reuses the existing neutral `PdfColor` cell background; no public API, artifact or dependency is added.

Original theme-fill structures, namespaces, duplicate properties and transforms are checked before binding. Qualified clear/automatic foreground fills are supported; patterned shading and unqualified foreground effects remain explicit failures. Theme palettes retain existing guards. No unsupported construct is silently treated as a plain background.

`WordThemeColorsTest` covers palette precedence, transforms, none semantics and malformed original XML. `NativeThemeCellFillTest` uses direct/inherited synthetic A4/Letter sources, with actual Word exports and separate explicit RGB controls. All twelve markers per paper differ by at most .091 points horizontally and .081 points vertically. Five sampled colors match Word exactly; the sixth pair has no fill. Direct and inherited native pixels are identical. New initial pages were visually reviewed and are guarded by exact image comparison. Standard PDF and PDF/A-1b/2b/3b use independent validation; existing goldens remain unchanged.

The Playground uses this same native reader for generation and cleanup. No option, UI behavior or release/site version changes are introduced.
