# 0.2.0-alpha.1 preparation

Selected version: `0.2.0-alpha.1` for all five libraries, server and separate Playground application. Contract version is `0.2`: a `0.2` runtime accepts compatible `0.1` providers, while providers/pipes using the new APIs require at least `0.2`. Old runtimes must reject that metadata before implementation initialization. Java 17 remains the library baseline; the server and Playground use Java 25 and isolated validators use Java 17.

This records the preparation checklist, not reusable publication approval. Historical libraries (`0.1.0-alpha.1`) and production images through `0.1.0-alpha.3` remain immutable. No prerelease gets `latest`. The site paths and Pages behavior below describe the signed release source before repository separation; current website ownership and qualification paths are defined in [the branch workflow](branch-workflow.md). Deployment still requires explicit authority.

The source/signing, four native archive reviews, corresponding-source provision and public Maven/production/Playground artifact verification are complete; see the [distribution review](release-0.2.0-alpha.1-audit.md). The maintainer published the validated libraries directly in Central, so no second publication request was made. Matching website files and all nine download ZIPs are prepared; deployment and live checksum verification remain the final handoff. The maintainer explicitly requested no additional local document generation/renders during this release; the seventh image scenario has structural checks and no new manual visual/client qualification claim.

## Source and offline checks

- Run the full build, independent Java 17/Kotlin consumers, contract discovery/backward-compatibility tests and release-tool tests.
- Qualify the final production and Playground candidates on native AMD64 and ARM64. Verify isolation, truthful SBOMs, health endpoints, fonts/extensions and the Playground development warnings separately. A previous candidate's audit does not cover these changed images.
- The original staging procedure runs from the signed `v0.2.0-alpha.1` source, whose website contains the six-example baseline: `python -X utf8 tools/site/prepare_release_site.py --output build/release-0.2.0-alpha.1/site --version 0.2.0-alpha.1`. Do not rerun this authoring procedure over the later seven-example site. Use a fresh directory; the tool refuses existing or non-build destinations. It needs the same Python document/image dependencies as the existing example builder.
- Check the draft structure with `python tools/site/check_site.py --site-directory build/release-0.2.0-alpha.1/site --allow-preparation`. This explicitly permits an unfinished release draft; it is not a publication check.
- Exercise `tools/site/verify_example_kit.py` against the candidate image with `--kit-directory` pointing to the staged downloads. The seven scenarios include a real Base64 PNG inserted with `{image | image(60, 25, true)}`. Maximum dimensions are millimetres; this 3:1 image should become 60 by 20 mm. Other scenarios supply null and omit the illustration. Inspect rendered pages, not only XML/text assertions.

## Required before distribution

- Merge reviewed, signed preparation source after all required checks pass, then repeat the protected actual-key signing/consumer check on the exact candidate. Local disposable-key tests do not replace it.
- Complete separate server and Playground license/source/security reviews for both architectures, including both JVM runtimes, renderer, validators and application JARs. Make matching source materials anonymously retrievable before binary transfer. Bind review records to the exact signed source, image configurations, archive hashes and all twenty library artifacts; never fabricate an approval or reuse a historical acceptance implicitly.
- Create and verify the signed annotated release tag only under release authorization. Archive/upload/publish workflows are manual and protected; merging preparation does not dispatch them.
- Upload signed libraries in USER_MANAGED mode, inspect Central validation, and only then perform the separately protected final publish step. Handle uncertain outcomes manually rather than retrying blindly.
- Publish the two audited OCI indexes with both native architectures and no `latest`. The new Playground package may need an administrator to enable public visibility; verify anonymous pulls for both packages.

## Website handoff after availability

The staging tool writes `RELEASE-PREPARATION.txt` and a `REQUIRED_APPROVED_RELEASE_DIGEST` placeholder instead of silently carrying over the old Helm digest. Do not copy this draft to the live site. Before removing the marker, finish current-release wording and endpoint lists, add the Playground deployment/download instructions, bind the exact approved Helm digest/chart metadata, and verify all advertised public artifacts. Rebuild every affected ZIP and the complete starter kit, pass the normal site/Compose/client checks, then publish only with deployment authorization and verify live HTTPS/download checksums. The release is not fully handed off before that verification.
