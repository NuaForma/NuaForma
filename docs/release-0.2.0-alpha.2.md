# 0.2.0-alpha.2 preparation

Selected version: `0.2.0-alpha.2` for all five Java 17 libraries and both Java 25 applications. Production and separate development Playground each require native AMD64 and ARM64 images. Prereleases never publish `latest`; previous artifacts and tags remain immutable.

This is a preparation record, not a completed audit or publication claim. Release authority was given on 2026-10-22. Follow the [container procedure](container-releases.md), [Maven procedure](maven-central-releases.md) and [branch/website coordination](branch-workflow.md). Preparation enters develop through a signed, checked PR, then a release-promotion PR enters main. Only main may run protected release workflows.

## Candidate scope

Changes since alpha.1 include configurable bounded PDF parallelism and provider-readiness improvements (#91), Sonar/coverage and reviewed dependency verification (#94), extension archive hardening (#96), safe structured rejection explanations (#98), resilient mounted-font catalogs (#99), optional embedding of used fonts from configured catalogs (#100), and the default-on operator PDF/A conformance policy (#93). The disposable ODT/FOP experiment is not part of this release; LibreOffice remains the approved backend.

The running contract is `0.4`, already implemented on develop. Do not raise it again for this prerelease. The DOCX provider requires `0.3`, the PDF provider requires `0.4`, and compatible earlier minima remain accepted. Existing pipe extensions using only the original pipe APIs may still require `0.2`. Artifact version and contract minimum are independent.

## Before distribution

- Review product Markdown and the separate website against the exact candidate. Keep published alpha.1 coordinates truthful until alpha.2 is actually available; stage matching site updates without deploying unreleased download claims.
- Verify the build, Java 17 independent consumers, strict dependency checksums, real signing and applicable native/quality checks. Preserve renderer/validator isolation, font/barcode checks and default-on veraPDF validation. Reuse existing recorded evidence only within its stated scope; do not claim unexecuted checks.
- Collect exact production/Playground inventories on both native architectures. Review original notices, matching source, both JVMs and current advisories. Historical alpha.1 risk acceptance does not approve new findings or this candidate; request explicit disposition for unresolved applicable risks.
- Publish and anonymously verify exact corresponding-source materials before any binary transfer. Separate `release-materials/0.2.0-alpha.2*.json` and `release-approvals/0.2.0-alpha.2*.json` records must bind the actual source, inventories, archives and twenty library hashes. Do not generate approval merely from a successful build.
- Create the signed immutable source tag only once the candidate is reviewed. Use protected archive/upload/publication workflows, inspect uncertain outcomes and do not retry uploads or overwrite tags blindly. Central's manual Publish action is coordinated explicitly to avoid duplicate requests.

## Website gate and handoff

Review template/extension examples, font behavior, diagnostic privacy, parallelism and conformance-policy documentation before release. Include the existing staged short-trial performance page, retaining its original source identity, counts and limitations; it is not a new alpha.2 benchmark. Explain that only exact `NUAFORMA_PDFA_VALIDATION=false` skips veraPDF, never PDFBox or isolation, and does not certify conformance.

After actual artifact availability, bind both OCI index digests and the immutable runtime profile source, update library/download snippets and release status, repackage all nine kits without regenerating unchanged Word templates, promote the website through its checks, and verify live HTTPS pages and every ZIP checksum. Preserve the current URLs, separate development warnings and the requirement for deployment-specific sandbox readiness. Do not claim unrestricted OpenShift compatibility or universal layout fidelity.
