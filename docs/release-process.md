# Release with one final approval

Stable versions publish to Maven Central; immutable prereleases publish to Nexus. Every green develop push publishes a signed timestamped snapshot. See [repository setup and version rules](nexus-publishing.md).

The maintainer approved reusing merge qualification on 2026-10-30. A release builds the selected version once and publishes those bytes after one final approval. It does not repeat tests already passed for the same inputs. The 10–15 minute preparation target is a goal, not a measured production guarantee; runner queues, source changes and publication services affect elapsed time.

## Qualify changed inputs once

The Java 17/25 and native AMD64/ARM64 jobs retain small full-qualification receipts only after their actual tests finish successfully. Each receipt identifies its workflow run/attempt, source commit, tested checkout, lane and input fingerprint. Java reuse also requires the real SonarCloud Code Analysis check from `sonarqubecloud` to have passed on that original source. A scanner upload or generic status is insufficient.

On a later PR or develop/main push, the same required check names either run full qualification or validate a matching complete earlier qualification. Reused jobs link the original run in their summary; they do not upload new full receipts or claim a new Sonar analysis. Branch protections remain in place. The signed merge/source still identifies the delivered revision.

Java fingerprints cover tracked build, code, tests, fixtures and workflow inputs. Only the version value in `gradle.properties` is normalized. Documentation/reference-review records (`docs/`, release notes/materials, branding and README/agent instructions) do not change runtime qualification; their review and release checks remain separate. Native fingerprints cover module/build/runtime/container/qualification inputs. Changed inputs, missing/expired evidence or no matching run require full qualification; failed, foreign, incomplete or inconsistent matching evidence cannot authorize publication. Manual Build/native dispatch forces full qualification. Evidence is retained for 90 days and discovery directly queries the input fingerprint and is bounded to the latest 30 matching receipts; older unavailable evidence causes full checks, not a bypass.

Snapshots check the signed develop commit and current Java jobs, then the same full Java/Sonar qualification. They do not force Sonar to analyze an identical merge tree again. Snapshot generations retain the same `X.Y.Z-SNAPSHOT` coordinate; a new timestamp/build number does not automatically increase the semantic version. Alpha/stable version decisions remain explicit.

## Prepare the selected version

1. Merge the reviewed changes into develop, then promote develop to main through the normal checked PR. Identical tested inputs reuse qualification; no administrator bypass or fabricated Sonar status is needed. Create the verified signed annotated `v<version>` tag at the selected main commit.
2. Dispatch **Release with one final approval** on main. Supply the version and matching published source baseline. Native CI is discovered automatically; an optional run ID may prioritize a full qualifying run. Both actual architectures and exact runtime inputs must match.
3. Build/sign the six libraries and both shared application distributions once. Check artifact/POM/Java-baseline identities and all 24 signatures. This does not rerun consumers or the complete test suite.
4. Build all four native images in parallel from the shared distributions. Compare platform, non-root configuration, source/version labels and actual component inventories against the reviewed baseline. Changed dependencies/runtime inputs require new qualification/source coverage. Endpoint, visual, font and load qualification stays in CI.
5. Retain large JVM/image archives for publication and separate small verified evidence for review. The review job downloads only the evidence and assembles the candidate. It does not download, unpack or hash the large archives a second time.

Matching source archives are reviewed once when the dependency/runtime graph changes. Original sources/notices remain preserved; existing releases and source assets are not replaced. For an unchanged baseline, anonymously query GitHub's public release metadata and require each exact source URL, uploaded state, size bound and server SHA-256 to match the recorded archive hash. Do not download hundreds of megabytes again merely to compare an already reviewed identical archive. Missing public metadata/digests stop preparation. New archives still require the original full source/notice review and checksum verification before their source-only `sources-v<version>` publication. The candidate and in-image source notice bind the exact baseline/URLs/hashes.

## One final approval and publication

Only `publish` uses the protected **release** environment: maintainer reviewer, main only and no administrator bypass. The review covers the complete candidate, current documented runtime/security findings, Maven destination, both container indexes, GitHub release and matching website. An earlier release's exception does not transfer.

After approval, download the large archives once. The publisher rehashes them once against the candidate, checks loaded image identities, refuses existing immutable versions and publishes without rebuilding. Stable versions retain Central validation/publication; prereleases use Nexus. Only the selected destination credentials are loaded.

Nexus uses at most six workers with a persistent TLS connection per worker. Preflight checks remain anonymous and complete before writes. Each bounded write batch records all pending/acknowledged paths; failed/uncertain writes are never automatically retried, and no next batch begins after failure. Module metadata follows the complete artifact upload. Anonymous downloads verify all actual artifacts, detached signatures and module indexes. Checksum sidecars are checked locally and acknowledged on upload, rather than individually downloaded again. All connections close when the operation ends.

Production and Playground indexes retain both qualified native architectures and exact approved configuration identities. Prereleases leave `latest` unchanged. The website is prepared alongside the product; after publication bind the real source/index digests, run bounded static/package checks and promote its PR. Preserve existing document fixtures and unchanged pipe starters. Do not rerun product tests or regenerate Word examples for the site version change.

## Non-publishing rehearsal

Set **prepare_only** to true on main to exercise the full build/evidence/review path using the current source version and existing source baseline. No new release tag is required: the main source commit must instead be verified signed and fully qualified. The publication job is skipped, no final deployment approval is requested, and the candidate is marked `preparationOnly`. The publisher independently refuses such a candidate, including attempted manual recovery. Libraries/images remain workflow artifacts; no Nexus, Central, registry, GitHub release or website mutation occurs.

## Failure and recovery

Retain the candidate, original bytes and receipts. Uploads/pushes are never automatically retried; read-only propagation checks may repeat. Inspect actual Nexus/Central/registry/GitHub state before recovery, and resume only missing already-approved operations. Never rebuild or overwrite an immutable version. A new candidate requires a new final review.

Legacy protected recovery workflows remain available for their old review records. Do not dispatch them alongside this unified procedure. No secret values belong in logs, artifacts, Git or receipts.
