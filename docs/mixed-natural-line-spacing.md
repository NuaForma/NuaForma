# Mixed natural line spacing

Unreleased source qualification under [#241](https://github.com/NuaForma/NuaForma/issues/241).

## Physical line measurement

Automatically spaced paragraphs measure the supplied font runs on each physical wrapped line. The line uses the maximum natural ascent, descent and gap needed by that line, together with its paragraph-mark minimum metrics. A large word therefore enlarges its own line instead of imposing the same large box on every line in the paragraph. A list label participates in its first line.

The existing automatic multiple scales that natural height. An `atLeast` floor retains a larger natural box and adds leading above the baseline when the floor is larger. Glyphs are never scaled to meet a spacing policy. Explicit exact spacing keeps its fixed height.

Internal measurement retains each line's element range, origin and height. Body pagination, paragraph splitting, table fragments, page bands, frames and notes consume those individual heights. Paragraph borders use the same physical line extents. Uniform lines retain their existing arithmetic and reviewed output.

## Boundaries

Mixed natural metrics in bidirectional paragraphs remain outside this qualification. Variable natural line heights beside floating-image exclusions also remain rejected, including when variable heights only emerge after rewrapping. Existing inline-image/vector, shaping, glyph, font-embedding, overflow and source-profile limits still apply; this increment does not implement general Word typography or font substitution.

All geometry is request-local. Cancellation and resource bounds remain enforced. No public API, dependency, package or distributed font is added. The existing format-neutral spacing policies remain available to a future ODT adapter.

## References and regression policy

`nuaforma-docx-pdf/src/test/resources/mixed-natural-lines` contains synthetic A4/US Letter sources and actual two-page Word exports using Liberation Sans Regular. Both exercise 8/10/16/20-point runs, wrapping, single/1.5 spacing and a 14-point minimum. Rebuild new sources with `tools/pdf-evaluation/own-engine/build_mixed_natural_fixture.py --base <paragraph-metadata-directory> --output <fresh-directory>`; the existing Word exporter records independent source, PDF and fixture-font hashes.

All four output profiles compare text and glyph positions per page against Word and compare their 96-dpi rasters exactly against initially reviewed engine goldens. The four new golden pages are immutable references; changing them requires a specific reason and approval. Existing golden files are preserved. Shared tests cover individual heights, pagination, minimum metrics, multiples, exact spacing, table pieces, borders, page bands, frames, notes and the explicit unsupported boundaries.

The Playground inherits the conversion improvement through its existing provider. No new control or product release is introduced. Private corpus sources and locally supplied evaluation fonts stay outside Git and CI.
