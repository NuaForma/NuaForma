# PDF/A-3a feasibility research

Trusted synthetic-fixture research only, outside the product Gradle graph. No production profile, API, automatic repair, stable compatibility guarantee or publication is implemented. See [the findings](../../../docs/pdfa-3a-feasibility.md).

## Pinned prerequisites

Use the existing qualified Linux/amd64 service image and Java 17. The measured image identity is recorded in the findings. All PDF conversion, inspection, metadata experiments and veraPDF validation run under Bubblewrap with the existing immutable renderer/validator roots, cleared environment, private request files, no network and bounded resources. The outer research containers also have no network or ports, run non-root/read-only, drop capabilities and use the existing reviewed container filter, init, CPU/process/memory limits and bounded tmpfs. Every container has a fresh owned name/label and is removed only after ownership verification, including on timeouts. QA outputs are deliberately retained, not installed globally.

Obtain the official [veraPDF Greenfield 1.30.3 installer](https://software.verapdf.org/releases/1.30/) and detached signature. Verify the full documented fingerprint `13DD102B4DD69354D12DE5A83184863278B17FE7` from [the installation guide](https://docs.verapdf.org/install/), not only a short key ID. Expected installer SHA-256 is `b512bf24945503a33fc10738ecffc1f2dccaaf8593a448e94ee9ac86a9559a59`. Retain the archive, signature, public key and verification evidence under ignored `build/pdfa-feasibility/downloads`.

Install only CLI using the adjacent `install.xml`, Java 17 in a non-root/networkless/read-only container, read-only installer/config mounts and an initially empty dedicated writable `/installation` mount backed by `build/pdfa-feasibility/installation`. Never point the installer at existing user files. The resulting `installation/cli/bin/cli-1.30.3.jar` SHA-256 is `19f6edd5c92841a38d9b3b018a555b7ba173b13e4e0d29edecc232e5ba6c1d70`. No GUI or sample plugins are installed. Do not change the product Dockerfile or dependencies.

Compile the two independent probes with Java 17 and the already approved PDFBox distribution:

```sh
javac --release 17 -cp 'nuaforma-docx-pdf/build/pdf-validation/*' -d build/pdfa-feasibility/probe tools/pdf-evaluation/pdfa3a/PdfaProbe.java tools/pdf-evaluation/pdfa3a/SemanticProbe.java
```

The matrix reuses existing generated synthetic fixtures and explicitly selected private interoperability fonts. It requires the prior service qualification's `document.docx`, the approved prepared-copy fixture matrix and generated QA font copies under the paths named in `matrix.py`. Recreate those through their documented builders if absent; do not silently substitute customer documents or change source references. The round-trip sample intentionally retains literal placeholders; generated complex/barcode/watermark samples exercise existing completed transformation output. Only temporary copies receive explicit `en-US` language and the known inline image description; original source hashes must remain unchanged.

## Execute and verify

Use fresh output directories and pass the Docker executable/image explicitly:

```sh
python tools/pdf-evaluation/pdfa3a/run.py --image nuaforma:local --work build/pdfa-feasibility/version --mode version
python tools/pdf-evaluation/pdfa3a/matrix.py --image nuaforma:local --output build/pdfa-feasibility/matrix-final
python tools/pdf-evaluation/pdfa3a/negative.py --image nuaforma:local --matrix build/pdfa-feasibility/matrix-final --output build/pdfa-feasibility/negative-final
python tools/pdf-evaluation/pdfa3a/headings.py --image nuaforma:local --matrix build/pdfa-feasibility/matrix-final --output build/pdfa-feasibility/headings-final
python tools/pdf-evaluation/pdfa3a/finish.py --image nuaforma:local --matrix build/pdfa-feasibility/matrix-final
python tools/pdf-evaluation/pdfa3a/verify.py build/pdfa-feasibility/matrix-final build/pdfa-feasibility/review-final --poppler <bundled-poppler-directory> --decoder <approved-zxing-cpp-directory>
python -m unittest discover -s tools/pdf-evaluation/pdfa3a -p 'test_*.py' -v
```

The matrix compares standard, tagged PDF/A-3b and combined 3b/PDF-UA-flag exports. That flag is diagnostic, not a PDF/UA support claim. Metadata-only copies change the exact 3b identifier to 3a **solely to measure the remaining profile gap**, then undergo explicit 3a validation. The original exports/files are retained. Changing the identifier does not by itself establish conformance or meaning and is not offered as product repair.

`report()` requires the expected profile, normal completion, one job, complete consistent check totals and no parse/encryption/memory/veraPDF exception. False, missing, wrong-profile and aborted reports are never positive results. Semantic evidence records effective structure roles and tagged reading order. The missing-description experiment is an explicit observation of a machine-check limitation, not a passing semantic acceptance test. Report-policy unit tests do not replace actual validation.

The verification tool checks all pages, embedded font streams, no attachments/forms/encryption, exact pixel parity and whole-page Code 39 scans at 100/300 DPI. Inspect all distinct page images. The existing native sandbox probe can also be staged into a private work directory with its `visible.txt`; put synthetic `/app` and `/other` sentinels outside that work tree under `build/pdfa-feasibility/sentinels`, then run the `sandbox` mode. No actual secrets or corporate data are involved.

## B-profile production follow-up

The maintainer subsequently selected PDF/A-1b, PDF/A-2b and PDF/A-3b, with 3b as the default, and approved production integration after the StAX gate was resolved. The production adapter uses unmodified upstream library JARs with the unused StAX dependency excluded, not a repackaged CLI or a metadata relabeling step. The helper `stax_exclusion.py` removes only StAX entries from a separate research copy to establish report-path dependence; no remaining class/profile bytes are changed. `b_profiles.py` compares official JSON reporting before/after that exclusion. Raw reports are retained; per-JVM identity hashes in diagnostic context strings are excluded from comparisons, not rule/check counts, predicates or outcomes.

`native_validation.py` exercises the production adapter against the same bounded corpus and deliberate invalid files. `validate_service.py` uses the verified official CLI independently on actual HTTP response bytes. `verify_b_profiles.py` measures page parity and scans across profiles; `verify_service.py` checks final identifiers, embedded fonts, no forms/attachments/encryption, high-resolution scans and opaque-watermark parity. A transparent 1b watermark is a deliberately unsupported diagnostic case: the product rejects it instead of accepting the measured border loss. These helpers remain synthetic QA tooling; see the [product guide](../../../nuaforma-docx-pdf/README.md) for current supported behavior.

```sh
python tools/pdf-evaluation/pdfa3a/stax_exclusion.py --image nuaforma:local --output build/pdfa-feasibility/stax-exclusion
python tools/pdf-evaluation/pdfa3a/b_profiles.py --image nuaforma:local --excluded build/pdfa-feasibility/stax-exclusion/excluded --output build/pdfa-feasibility/b-profiles
python tools/pdf-evaluation/pdfa3a/native_validation.py --image nuaforma:local --matrix build/pdfa-feasibility/b-profiles --output build/pdfa-feasibility/native-validation
python tools/pdf-evaluation/pdfa3a/validate_service.py --image nuaforma:local --service <service-qualification-directory> --output build/pdfa-feasibility/service-validation
python tools/pdf-evaluation/pdfa3a/verify_service.py <service-qualification-directory> build/pdfa-feasibility/service-review --poppler <bundled-poppler-directory> --decoder <approved-zxing-cpp-directory>
```

The helper corpus paths are explicit in their source; retain/recreate prerequisite fixtures through their documented builders. QA never installs customer fonts globally or authorizes publishing an image. Production staging retains exact source/notice/profile materials through `tools/container/prepare_verapdf_materials.py`, not the research installer directory.

## Original feasibility-study license/source boundary

The maintainer approved veraPDF under MPL-2.0 for this study. Retain its original dual-license notices without relabeling vendor files as Apache-2.0. The validation profiles are separately CC-BY-4.0: retain consortium attribution, license text and exact packaged XML. Other components retain their own licenses, including Saxon/Rhino, JAXB, Jackson, XML Resolver and the legacy StAX utility.

`materials.py <exact-cli-jar> <fresh-output-directory>` archives embedded notices/POMs, the tested profile XML and matching Maven source JARs/POMs with checksums. Five components without embedded Maven identities were cross-checked against class constants/embedded build dependencies. This is research provenance, not a complete final-image license/security audit; retain published license texts and review parent/build/resource obligations before any distribution. The 2007 StAX dependency needs an explicit maintenance/security disposition before production adoption, not an implicit waiver of the project dependency policy. Nothing here downloads dependencies during document generation or authorizes an image, library or source-bundle publication.
