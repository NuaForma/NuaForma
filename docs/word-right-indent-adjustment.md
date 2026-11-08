# Automatic right-indent adjustment

Unreleased source qualification under [#237](https://github.com/NuaForma/NuaForma/issues/237).

Word's `adjustRightInd` paragraph switch controls automatic right-indent adjustment when the current section uses a document grid. Its value inherits through paragraph styles. See Microsoft's [AdjustRightIndent reference](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.adjustrightindent).

The native reader validates all section grids before normalizing this switch. The existing grid profile accepts absent/default grids; active line/character grids remain unsupported. Within that inactive profile, valid unique on/off switches in defaults, styles and stories cannot change the right indent and are accepted. Normalization affects only the private print model: supported source and cleanup bytes retain the switch. Invalid, duplicate, nested, foreign-namespace and misplaced properties remain errors. An explicitly disabled switch does not enable an otherwise unsupported active grid.

The synthetic A4 source has no grid; US Letter uses an explicit default grid. Both carry default/style/direct adjustment switches and retain the wrapping and signed/hanging indents of the earlier automatic-text-spacing fixtures. Actual Word exports are stored with source/PDF/font provenance under `nuaforma-docx-pdf/src/test/resources/right-indent-adjustment`. Rebuild sources with `tools/pdf-evaluation/own-engine/build_right_indent_adjustment_fixture.py --base <automatic-text-spacing-directory> --output <fresh-directory>`; author Word references with the existing exporter and Liberation Sans Regular fixture.

Standard PDF and PDF/A-1b/2b/3b must match Word glyph positions within 0.75pt horizontally and 0.4pt on the baseline. Renders must also match the unchanged automatic-text-spacing goldens exactly and match otherwise identical sources with the adjustment switches removed. No existing golden is replaced. Active/malformed grids remain explicit provider failures without partial output.

The shared PDF model, public API, dependencies, runtime font bundle and Playground controls are unchanged. The Playground uses the same provider and cleanup qualification. This does not implement active grid layout or claim a published release. Private corpus documents and local fonts remain outside Git and CI.
