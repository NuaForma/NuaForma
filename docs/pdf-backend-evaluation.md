# Early DOCX to PDF backend evaluation

Research for issue #10, 2026-10-11. No production renderer has been adopted. The comparison is reproducible through [the separate research tooling](../tools/pdf-evaluation/README.md), which does not change NuaForma's dependencies, published artifacts or engine contracts.

## Decision status

Neither tested route currently satisfies the complete early-product fidelity requirements. LibreOffice is the stronger candidate for requested watermarks, but breaks the existing rotated VML barcode fixture. docx4j/FOP embeds the barcode font and produces a scanable barcode, but loses its rotation and the requested watermarks. Adopting either unchanged would silently weaken agreed behavior.

Recommended next investigation: a bounded LibreOffice-specific conversion compatibility layer for the rotated VML text box, applied only to an intermediate copy and never to the returned DOCX. This is a proposal, not an approved architecture decision or implemented fix. Confirm a correction against the independent expected fixture, barcode decoding and all-page visual comparison before choosing a backend. Do not replace barcode text with an image, change the DOCX template profile or silently drop unsupported content without a separate decision.

Backend approval remains outstanding. Issue #10 must remain open; #11 production conversion cannot start merely because the executables can produce PDFs.

## Compared versions and method

- LibreOffice 26.8.1.1, build `bf6ef2662401c7d6dce671ab4deb26ee0af3a7c1`, Windows x86-64, headless conversion with a private profile per document.
- docx4j-export-fo 17.1.0, JAXB ReferenceImpl 17.1.0 and Apache FOP 2.11 on Java 17, with the XSL exporter and explicitly supplied font files. The visitor exporter was also run on the barcode and five-page watermark fixtures.
- Identical synthetic DOCX inputs for each primary renderer, recorded by SHA-256. Expected/generated pairs are built independently by the existing tests. Earlier Word renders are local interoperability references, not a production dependency or a guaranteed cross-renderer pixel baseline.
- Arial regular, bold, italic and bold italic were explicitly available to FOP from the local host for this private experiment; they are not redistributed or a Docker prerequisite. Libre Barcode 39 is the approved OFL-1.1 test font. A unique-family embedded-font control proves that the result does not merely come from the installed barcode family.
- Poppler rasterized all output at 100 DPI. pypdf 6.10.0 inspected PDF headers, geometry, extracted text and font descriptors; Pillow 12.3.0 and ZXing-C++ 3.1.1 independently decoded raster pages. All 95 pages were covered by visual review of their 48 distinct PNGs, with identical SHA-256 PNGs sharing the same review. This review identifies defects; it is not a passing fidelity certification.

All 28 primary conversions and both visitor probes produced readable PDFs with zero converter exit codes. This is specifically not evidence that every document was rendered correctly.

## Observed results

| Fixture group | LibreOffice | docx4j/FOP |
| --- | --- | --- |
| Before/after round trip, two pages | Same raster pages within the renderer; portrait/landscape geometry retained | Same raster pages within the renderer; portrait/landscape geometry retained |
| Complex expected/generated, two pages | Expected/generated raster pages match; conditions and repeated content appear | Expected/generated raster pages match; conditions and repeated content appear |
| Tables, text and section geometry | Content and section dimensions retained; spacing and VML frame appearance differ from Word | Content and section dimensions retained; table edges and text-box positioning differ from Word |
| Ordinary VML and DrawingML text boxes | Text appears, but frame appearance/spacing differ | XSL places modern text inside the legacy box; visitor probe omits the modern text-box content |
| Rotated VML barcode, expected/generated | Barcode is broken into separate wrapped segments; independent decoding finds no barcode | `NF001` decodes as Code 39, but the barcode is horizontal instead of the requested quarter-turn and the frame extends to the page edge |
| Embedded barcode and unique-family control | Unique-family font is embedded in the PDF, but rotated layout remains broken and unscanable | Unique-family font is embedded and `NF001` decodes; rotation/frame fidelity still fails |
| Requested concept watermark, five pages | Visible on all five pages across portrait/landscape, first/even/ordinary headers | Absent on all five pages, in both XSL and visitor probes |
| Dynamic watermark | Resolved `CONCEPT — Ada` appears on all five pages | Absent |
| Color, opacity and rotation | Teal diagonal concept, pink vertical review and opaque horizontal watermark visible on every page; fully transparent output is raster-identical to the no-watermark control | Every primary watermark variant is raster-identical to the no-watermark control: these objects are dropped |

Both primary renderers retain two-page pagination for the round-trip, complex and ordinary barcode cases, one page for the isolated embedding controls, and five pages for each watermark case. All watermark variants retain their own renderer's page count and section sizes. That does not make Word and these renderers equivalent: the Word reference uses noticeably different paragraph spacing and text-box geometry.

The XSL and visitor comparisons agree on missing rotation and watermarks, so changing that exporter flag does not solve the blockers. The visitor's missing modern text-box content is an additional reason not to accept it as an automatic fallback.

## Maintenance, licenses and dependencies

The [docx4j 17.1.0 announcement](https://www.docx4java.org/forums/announces/docx4j-17-1-0-released-t3180.html) describes recent PDF layout work and Java 17 compatibility. The FO exporter is maintained inside [the main docx4j repository](https://github.com/plutext/docx4j/tree/VERSION_17_1_0/docx4j-export-fo); the archived older `docx4j-export-FO` repository is not the source of this dependency. Current FO changes were present on 2026-10-11. [FOP 2.11](https://xmlgraphics.apache.org/fop/2.11/) is the resolved version and is [Apache-2.0](https://github.com/apache/xmlgraphics-fop/blob/fop-2_11/LICENSE).

The locked research graph contains 63 Maven coordinates, including Batik 1.19, FOP 2.11, JEuclid 3.1.14 and three docx4j font resource artifacts, in addition to the existing DOCX stack. JEuclid's [parent POM](https://repo.maven.apache.org/maven2/de/rototor/jeuclid/jeuclid-parent/3.1.14/jeuclid-parent-3.1.14.pom) declares Apache-2.0 and points to the active [rototor fork](https://github.com/rototor/jeuclid). Legacy transitives already recorded in [the dependency review](../DEPENDENCIES.md) remain a maintenance consideration; an actively maintained parent project is not proof that every transitive is equally current. Bundled font artifacts contain Apache-2.0, OFL-1.1 and Bitstream/Arev font notices; keep each artifact's original notices and font naming conditions. The explicit OSV batch query for all 63 locked coordinates returned no known matches on this date; this is a database snapshot, not proof of safety. Consult [Apache's security notices](https://xmlgraphics.apache.org/security.html) when pinning the runtime.

LibreOffice's [release history](https://www.libreoffice.org/release-notes/) lists 26.8.1 as the recent branch and 26.2.6 as the mature branch recommended for enterprises. This experiment verifies only the installed 26.8.1.1 build; adopting another line requires rerunning the same fixtures. Its [security advisories](https://www.libreoffice.org/security/) include recent fixes for external-resource/file-access issues, reinforcing the need for isolation even on a current release.

LibreOffice is [MPL-2.0 with additional component licenses](https://www.libreoffice.org/licenses/). MPL's [file-level copyleft permits larger works under other terms](https://www.mozilla.org/en-US/MPL/2.0/FAQ/). An unmodified separately executed renderer does not require relabeling NuaForma as MPL, but distributing it in a container still requires applicable notices, source availability and a review of the actual installation's component licenses. Do not describe the entire image as Apache-2.0 or assume that installing it externally removes redistribution duties when bundling it later.

[ONLYOFFICE DocumentBuilder](https://github.com/ONLYOFFICE/DocumentBuilder) is a technical alternative without a full editor, but was not installed or tested. Its AGPL/commercial licensing needs a separate integration-specific review and user approval. A separate HTTP connection is not, by itself, proof of license compatibility or incompatibility. No claim about its comparative fidelity is made.

## Resources, isolation and Docker feasibility

Each primary conversion started a fresh process/profile. Recorded elapsed time was 9.530–11.785 seconds for LibreOffice and 8.303–12.597 seconds for FOP. FOP's process peak RSS was 281,668–306,944 KiB with a 512 MiB heap ceiling. The research launcher and all its runtime JARs occupy about 35.1 MB. LibreOffice peak process-tree memory was not measured. These are single-machine cold-process observations with fonts present, not throughput benchmarks, service limits or a fair cross-platform speed ranking.

FOP can run headlessly on Java 17 in Linux. LibreOffice offers a headless executable for Linux; neither requires Word, a cloud account or a commercial conversion service. A Linux container is feasible in principle, but this Windows LibreOffice versus Linux/JVM comparison does not verify Linux font lookup, LibreOffice packaging, startup cost or pixel parity. No Docker image has been built or claimed as working. Pin the selected renderer and image, preserve notices, then reproduce the cases inside the actual image before advertising Docker support.

Production conversion needs a non-root bounded worker, a private writable temporary area and font cache, explicit font resources, network denial, filesystem access restrictions, macro/active-content rejection, queue/concurrency bounds, output caps and cancellation/deadline enforcement with owned-process cleanup. For FOP, global font maps and caches must not cross tenants; an in-process Java library is not automatically request-isolated. For LibreOffice, `--headless` and a private profile are not a sandbox. The research harness's timeout and heap limit do not establish these production controls.

## PDF versions and profiles

Observed defaults are PDF 1.7 for LibreOffice and PDF 1.4 for FOP. The inspected fonts in these fixtures have embedded font streams, including the unique-family barcode control. This proves fixture-specific embedding, not universal font handling or permission enforcement by a future PDF provider.

LibreOffice documents [version/profile export switches](https://help.libreoffice.org/latest/en-US/text/shared/guide/pdf_params.html), including PDF version selection and archival/accessibility options. FOP documents [PDF/A settings](https://xmlgraphics.apache.org/fop/2.11/pdfa.html). Those are upstream options, not NuaForma capabilities: no PDF/A or PDF/UA validator was run, no empty signature-field workflow was tested and no profile is advertised. A future provider must describe and validate supported combinations and reject unverified/unsupported requests instead of guessing from a PDF header.

## Verification and remaining work

The separate research build compiled and installed on Java 17 with locked dependencies. The complete product build, architecture checks and publication metadata generation passed; test tasks reused their existing successful results because product code was unchanged. The rendering comparison and independent PDF/barcode inspection ran outside the product tests. Generated documents and reports remain ignored.

Before #10 is complete: resolve or explicitly decide the observed fidelity blockers, obtain the user's backend approval, and record the supported conversion profile. Before #11 ships: implement and test actual request/process isolation, cancellation, font resources, unavailable-converter behavior, both declared DOCX/PDF output combinations and no-duplicate watermark handling. Before claiming Docker or archival profiles: validate the real container and resulting profiles with independent checks.
