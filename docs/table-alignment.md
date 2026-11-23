# Horizontal table alignment

The development candidate adds `PdfTableAlignment.LEFT`, `CENTER` and `RIGHT` through `PdfTable.withAlignment`. Width negotiation finishes before alignment; `leftIndent` remains a physical offset from that alignment origin. Right alignment accounts for the outer right border. `rightOverflow` remains an allowed extent and does not move a table. Existing constructors retain left alignment. Body flow, page-band field resolution, nested table placement, semantic copies and note pagination retain the setting. Physical page and parent-cell bounds remain enforced.

The DOCX adapter validates original `w:tblPr/w:jc` values and namespaces before typed binding. In the qualified LTR profile, start/left and end/right map to the same physical alignments. Word ignores its declared table indentation when centering or right-aligning; this is resolved in the adapter. Older right-aligned tables reference the right cell-content margin and can extend into the page margin. The existing bounded right-overflow model retains that geometry. Modern Word percentage preferences use the containing text width less the nominal table border width; this source-specific reference is converted to points before shared width negotiation. Genuine Word layout measurements and PDF markers verify that reference.

Sixteen fixed-width Word controls cover legacy/modern compatibility, omitted/left/center/right declarations and a nonzero declared indent on A4 and Letter. Eight further controls cover modern automatic/preferred widths. `python3 tools/pdf-evaluation/create_table_alignment_fixtures.py <fresh-directory>` reproduces the package parts and refuses an existing directory. Initial native PNG references are authored only after content, geometry and visual review; older golden files remain protected.

Aligned nested Word tables, legacy aligned percentage widths and legacy right-aligned tables with custom cell borders remain explicit source rejections until separately qualified. Shared model callers can configure alignment independently of source-format policy. Native and actual Playground qualification is recorded after those checks finish. No dependency or package is added, no artifact is published and no Playground control changes.

All 24 genuine Word/native page pairs passed full-content, paper, geometry and visual review before their initial native golden PNGs were authored. Maximum marker differences are 0.151 point horizontally and 0.183 point vertically, within the existing 0.21-point bounds. The maintained matrix passes 36 standard/PDF-A profile PDFs with exact native pixels. Actual Playground requests produced 56 PDFs across standard PDF and PDF/A-1b/2b/3b; every page retained Word content/paper and exact provider pixels at 96 dpi without masks. Corpus verification kept all 250 sources unchanged and all previous 21 successful documents / 59 pages pixel-identical; four documents progressed to another source rejection. The complete combined normal build also passes.


## Repeated alignment on table rows

Word can record the same alignment on a table and its individual rows.
The DOCX adapter accepts those repeated values when they match the
effective table alignment. Independent row alignment retains a source
diagnostic. The native and Word control pages retain their positions when
a matching declaration is present.

[Word row alignment properties](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablerowproperties?view=openxml-3.0.1)
