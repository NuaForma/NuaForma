# PDF/A-3a feasibility findings

> **Historical research, not a supported product profile.** The maintainer subsequently chose PDF/A-1b, PDF/A-2b and PDF/A-3b only, with 3b as the default. The production veraPDF adapter excludes the unused legacy StAX dependency; level-A semantics were not implemented. See the [current PDF guide](../nuaforma-docx-pdf/README.md) and [B-profile follow-up](../tools/pdf-evaluation/pdfa3a/README.md#b-profile-production-follow-up). The original findings and recommendations below describe the study at its recorded baseline, not outstanding requirements to ship PDF/A-3a. Its Bubblewrap/custom-profile setup is historical; the current source container uses the [runtime default boundary](../deployment/pdf-runtime/README.md).

Measured on 2026-10-12, from source baseline `17670d0cbeb8823b53d80e8e9c30f69b2c1fbec3`. Research only: no product module/dependency/API/output change, production PDF/A capability, commit or image publication. Version remains `0.1`. The reproduction tools are [here](../tools/pdf-evaluation/pdfa3a/README.md).

## Outcome

**The existing renderer can support a narrowly qualified PDF/A-3a direction; a renderer replacement is not justified by this study.** LibreOffice exports tagged 3b. In these fixtures, the only additional machine-detected 3a failure is its `pdfaid:conformance=B` identifier. Controlled metadata-only copies independently pass the explicit veraPDF 3a profile, including complex content, text boxes and barcode output. This is evidence of technical feasibility, not permission to relabel arbitrary PDFs or a complete semantic conformance guarantee.

**Production must add semantic/template prerequisites alongside final-file validation.** The status watermark remains visible but its `CONCEPT` text is absent from tagged reading order. Removing the image description is not rejected by the tested veraPDF 3a profile. Real headings must be authored semantically: visually styled `Title` paragraphs map to `P`; an independently authored `Heading1` copy maps to `H1` while preserving identical page pixels. Do not claim meaningful status-watermark support or adequate alternative descriptions merely because machine validation passes.

## Fixed runtime and provenance

- Existing local service image identity: `sha256:d87b94e621db4a679e160860ef8977c251562233ab1d69679bd1161842842d55`, Linux/amd64, LibreOffice 26.8.1.1 and Temurin 17.0.20.1+1. The production image was not rebuilt or altered for this study.
- veraPDF Greenfield CLI 1.30.3, release 2026-10-07, installed CLI-only and run with a 256 MiB heap behind the existing sandbox. No GUI, sample plugins, caller profiles, scripts, automatic metadata fixing or dependencies fetched at runtime.
- Official installer SHA-256: `b512bf24945503a33fc10738ecffc1f2dccaaf8593a448e94ee9ac86a9559a59`; detached signature verified against the independently documented full fingerprint `13DD102B4DD69354D12DE5A83184863278B17FE7`.
- Installed CLI JAR SHA-256: `19f6edd5c92841a38d9b3b018a555b7ba173b13e4e0d29edecc232e5ba6c1d70`. Maven's CLI JAR has a different ZIP hash, but all 6824 class entries and packaged validation-profile XML are byte-identical, supporting exact artifact/source correlation.
- Existing approved PDFBox 3.0.8 performs bounded research inspection and the unchanged static-output safety check. No new PDFBox dependency is introduced.

All operations use owned non-root/read-only/networkless containers with init, dropped capabilities, no-new-privileges, the reviewed seccomp filter, bounded CPU/process/memory/tmpfs and a 150-second wall deadline. Bubblewrap independently clears inherited environment, denies network and exposes only the immutable renderer/Java/validator/research code plus the current private request directory. Child CPU/address-space/file limits remain enforced. Validation reports are file-size bounded to 8 MiB. A native probe passes all twenty boundary assertions, including absent application/extension/font-mount/sibling paths. All owned containers are removed; QA evidence remains local and ignored. These bounds qualify these fixtures, not arbitrary-document throughput or future shared generation deadlines.

## Norm and fidelity matrix

| Synthetic fixture | Pages | Direct tagged 3b | Direct forced 3a validation | Metadata-only 3a diagnostic |
| --- | ---: | --- | --- | --- |
| Previously generated simple letter | 1 | Pass | Fails B identifier | Pass |
| Round-trip layout reference, tables/image/text boxes | 2 | Pass | Fails B identifier | Pass |
| Previously generated conditions/nested repetitions | 2 | Pass | Fails B identifier | Pass |
| Previously generated rotated Code 39 margin barcode | 2 | Pass | Fails B identifier | Pass |
| Previously generated multi-section dynamic watermark | 5 | Pass | Fails B identifier | Pass, but status semantics incomplete |

Both tagged 3b and combined 3b/PDF-UA-flag exports yield this same machine result. No PDF/UA claim is made. Ordinary PDF correctly fails PDF/A checks for missing identification, color/structure and, in relevant fixtures, annotation/transparency requirements. No automatic fallback is involved.

The matrix has 15 renderer exports and 10 separately validated metadata-only copies. All 25 outputs / 60 page rasters retain exact per-fixture standard-output pixels and pagination; all twelve distinct representative pages were visually reviewed without new clipping, overlap or layout changes. All font streams are embedded, files are unencrypted and no attachments or forms are present. Each barcode variant independently decodes `NF001` at -90 degrees at 100 and 300 DPI. The additional explicit-heading copy validates against 3a, retains `H1` roles and has two pixel-identical pages. The accepted issue #32 modern-inline-text-box limitation remains unchanged, not widened into a general tolerance.

The source DOCX files remain hash-identical. Only research copies receive explicit `en-US` language and the known inline-image description; the layout-only round-trip fixture deliberately retains literal placeholders. This is a conversion/profile study reusing earlier engine-generated samples, not an implemented new end-to-end product profile or evidence that every template/data combination is supported.

## Negative and semantic evidence

- Removing the structure root, Unicode mapping, valid language tag or RGB output intent is detected by forced 3a validation (respectively clauses 6.7.3.3, 6.2.11.7.2, 6.7.4 and 6.2.4.3).
- Removing the image description still yields a positive machine report. The first experiment expecting a negative result failed and is retained; the subsequent experiment records this limitation explicitly, rather than treating it as semantic acceptance.
- A truncated PDF yields parse failure (CLI exit 7), not an accepted complete validation report. Wrong-profile, missing/aborted reports, zero-job/error/OOM totals and incomplete check counts are rejected by the research report policy; three unit tests include deliberate counterexamples.
- Two concurrent validations succeed with separate request roots/home/cache files. Ten final diagnostic outputs also pass the existing static PDFBox policy, not only veraPDF.
- Tagged text includes the barcode reference and native text-box text. Semantic inspection finds no `CONCEPT` watermark text, alternative description or equivalent status in the logical reading order, despite its visible vector-outline rendering and positive machine reports.

The [veraPDF documentation](https://docs.verapdf.org/validation/) distinguishes machine validation from human-review requirements. Mandatory request language must be distinguished from the precise norm rule, which validates an existing language identifier; this study does not prove that a syntactically valid supplied language matches the document's actual language. Likewise, meaningful alternative text and reading order require appropriate source information, not inference from pixels.

## Licensing and adoption gate

The maintainer approved using veraPDF under MPL-2.0 for this research. Original notices, MPL/GPL license texts, consortium/profile attribution and CC-BY-4.0 text are retained locally. An inventory records matching POM/source JAR retrieval for 25 observed components, including hidden bundled dependencies identified from bytecode/build POMs, with no failed downloads. Exact tested validation XML and embedded notices are preserved. Independently written NuaForma research code remains Apache-2.0; third-party code/assets retain their own terms.

The supplied CLI contains legacy transitive components, notably `net.java.dev.stax-utils:stax-utils:20070216`, alongside Saxon 12.8, Rhino 1.7.15.1 and older JAXB artifacts. The maintained veraPDF release does not automatically establish maintenance/security suitability of every bundled dependency. Before production adoption, investigate whether the StAX dependency can be eliminated/replaced in an upstream-compatible build, or obtain an explicit, reviewed policy exception. Do not quietly update/repackage the verified vendor binary or infer approval for a fork from this feasibility authorization.

Source JAR/POM/profile retention is not a completed distribution audit. Any future image must carry exact notices/attributions and recipient-accessible matching source/build/resource materials for all applicable licenses, including inherited obligations. Complete the existing [release gate](container-releases.md) again for the resulting final candidate. No binary, image, release, public endpoint or source bundle is published by this study.

## Recommended next decision

Proceed with an explicitly bounded product design using this renderer, not an immediate universal 3a claim. Define required request metadata and semantic template prerequisites, meaningful status-watermark representation, safe format-specific failures and exact final-byte validation without downgrade. Resolve the legacy transitive-dependency gate first. Qualify all production integration, readiness, deadlines, cancellation, cleanup, Java/Kotlin/REST parity and licensing on the final candidate before advertising support. Attachments remain rejected and unplanned; ordinary PDF/DOCX behavior and the unpublished 0.1 baseline remain unchanged.

Primary references: [official veraPDF release](https://software.verapdf.org/releases/1.30/), [download/signature verification](https://docs.verapdf.org/install/), [MPL licensing](https://github.com/veraPDF/veraPDF-apps/blob/v1.30.3/LICENSE.MPL), [validation profiles and attribution](https://github.com/veraPDF/veraPDF-validation-profiles), [LibreOffice export parameters](https://help.libreoffice.org/latest/en-US/text/shared/guide/pdf_params.html).
