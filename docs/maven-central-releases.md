# Maven Central release preparation

Target namespace: `dev.nuaforma`, verified by the maintainer through ownership of `nuaforma.dev`. The project website is hosted at `nuaforma.dev` through GitHub Pages. Those registrations are not artifact publication, website deployment or permission to distribute a container.

## Current state

The five libraries are independently staged to `build/library-repository`. Their POMs include coordinates, dependency versions, project/license/developer/SCM metadata; Java-17 main/source/documentation JARs contain the project license and third-party guide. Documentation JARs contain module guides, not generated Kotlin API pages. [Independent Maven/Gradle examples](../examples/embedded/README.md) verify actual use without source-project substitution, and runtime/POM checks guard optional dependencies.

The five libraries are published to Maven Central as `0.2.0-alpha.2`; see the [release](https://github.com/NuaForma/NuaForma/releases/tag/v0.2.0-alpha.2). Earlier alpha.1 coordinates remain immutable. Earlier `0.1.0-alpha.1` coordinates remain immutable. The original alpha upload/publish workflows remain fixed to that historical version and tag: do not rerun them to create a new version. The parameterized **Upload reviewed release to Central** and **Publish validated release on Central** workflows accept an explicit new version and require its own signed source tag and exact review records. Preparation itself does not upload; the separate Portal upload uses `USER_MANAGED` mode, not automatic publication, with no automatic retry after an uncertain outcome. See [Central's artifact requirements](https://central.sonatype.org/publish/requirements/).

The manually dispatched **Prepare Maven Central release** workflow is limited to `NuaForma/NuaForma` on `main`, protected by the `maven-central` environment. It builds, verifies standalone consumers, stages/signs the library artifacts locally and independently verifies the expected OpenPGP identity. It uploads only non-executable hash/verification records, never JARs, private keys, caches or a bundle. It does not create a release/tag, upload to Central, or use the Central publication credentials. Actual token validity is therefore not claimed by preparation.

## Maintainer configuration

The `maven-central` environment must require reviewer `mrhoeve`, disallow administrator bypass, allow only the exact `main` branch and allow self-review while there is one human maintainer. The helper verifies that actual configuration rather than relying on an environment name in YAML. Changes to the reviewed configuration require an explicit policy update.

Environment secrets:

| Name | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Portal-generated token username; reserved for a separately authorized publisher |
| `MAVEN_CENTRAL_PASSWORD` | Portal-generated token password; reserved for that publisher |
| `MAVEN_SIGNING_KEY` | ASCII-armored, passphrase-protected OpenPGP private key |
| `MAVEN_SIGNING_PASSWORD` | The key's passphrase |

The approved **public** primary fingerprint is `74317C6CE9C61B80A2A1C0B40F736E8D598CB08B`. The protected workflow obtains its public key from the documented keyserver and checks every signature against that fingerprint, rejecting altered bytes, missing/invalid signatures, expired/revoked keys and a different signing identity. Private key contents are supplied only to the signing step's environment and Gradle's in-memory signer; no secret is written into tracked properties, printed, cached by the workflow or uploaded. Git SSH signing remains separate and unchanged. Keep the key/password/revocation backup in the maintainer's password manager; do not share those values in an issue or chat.

A six-month Central token lifetime is an accepted practical expiry policy; the actual configured expiry remains the maintainer's responsibility. Keep credentials out of developer configuration files in the repository, replace them before expiry and revoke immediately on suspected exposure. Token replacement does not require changing the public signing identity. See [Sonatype token documentation](https://central.sonatype.org/publish/generate-portal-token/).

## Local verification without maintainer secrets

```sh
./gradlew build verifyEmbeddedConsumers
python3 tools/release/verify_local_signing.py --repository build/library-repository --output build/signing-qa
```

The latter generates a disposable, passphrase-protected synthetic identity in a private temporary GnuPG home, runs `stageLibraries -PsignArtifacts=true`, verifies all twenty required artifact signatures with a public-only verifier, checks deliberate tampering/wrong identity/missing signature rejection, and removes the test key. It requires GnuPG; it neither reads the maintainer's private keyring nor contacts Central. Local metadata records identify the **synthetic** identity and are not release approval. Normal builds need no signing credentials; explicitly requested signing fails when credentials are missing.

The protected actual-key check passed on 2026-10-12 in [run 38309211627, attempt 2](https://github.com/NuaForma/NuaForma/actions/runs/38309211627), against reviewed source `f8b774da8f76e43ad07bf440edf710b48727f374`. All twenty staged development artifacts were independently verified against the approved primary fingerprint; standalone consumer checks also passed. This establishes the configured private-key/password pair, not Central token validity or approval of a different release artifact. Repeat signature/consumer checks for every exact new candidate; the historical success is not its approval.

## Before each new publication

Release `0.2.0-alpha.2` uses contract version `0.4`; historical alpha.1 uses `0.2`. The parameterized upload/publish workflows require `release-approvals/<version>.json` (server) and `<version>-playground.json` to bind both applications' reviewed native platforms to the same signed source/version. The server record additionally requires `approved.libraryAudit: true` and the SHA-256 of exactly twenty POM/main/source/documentation artifacts under `libraryHashes`. No record or approval is inferred from successful tests. The upload rebuilds/signs the candidate and checks its artifact bytes against that record; final publication requires `VALIDATED` and checks all twenty staged bytes before its single publish request. If the maintainer already selected Publish in the Portal, do not submit another request: inspect the state and verify the public artifact hashes once available. See the [preparation record](release-0.2.0-alpha.1.md) and [distribution review](release-0.2.0-alpha.1-audit.md).

1. Use the reviewed, signed source on `main`; run the protected preparation job with the actual signing secret and retain accepted review records. Independent local consumers and disposable-key signing QA do not replace this actual-key check.
2. Recheck **all Markdown files** against the exact release candidate: distinguish implemented features, future roadmap, current options/examples and known limitations. The development documentation sweep on 2026-10-12 updated the root README, stale module status and historical-research boundaries; it does not replace checking final release coordinates/download instructions after a version is selected.
3. Select a new, unused immutable version explicitly; update module/provider metadata consistently and qualify artifacts from the signed release commit. Check current advisory/licensing findings and retain complete artifact/dependency notices. Re-run independent consumer and signature checks for that exact version.
4. Prepare the separately approved, protected publisher for that new candidate, with Central Portal credentials, an explicit manual publish decision, compliant bundle/checksums and failure/retry policy. Upload/staging at Central is an external distribution action, not a harmless local dry run; no such endpoint is called by this preparation.
5. Verify released artifacts can be retrieved through normal Maven/Gradle coordinates, with correct signatures, sources and documentation, before advertising availability.

The original `0.1.0-alpha.1` release published the five libraries and the initial service container. Later container-only alpha.2/alpha.3 releases did not republish those libraries. The local `prepare_central_bundle.py` helper assembles only the five modules' verified POM/main/source/documentation files, signatures and checksums in Maven layout. It rejects snapshots and overwriting an existing bundle and never uploads. Its packaging tests do not replace real-key verification for the exact candidate. See [Sonatype's bundle layout](https://central.sonatype.org/publish/publish-portal-upload/).

Two separately protected manual workflows implement the authorized Portal procedure. **Upload reviewed alpha to Central** checks the signed source tag and complete review before loading secrets, signs/verifies the exact candidate and uploads once in `USER_MANAGED` mode. **Publish validated alpha on Central** accepts only that deployment UUID, requires `VALIDATED`, independently downloads and hashes every staged artifact against the review, and then requests publication once. Both reject uncertain outcomes rather than retrying an upload/publish automatically. A successful HTTP publish request is not `PUBLISHED` or proof that normal Maven coordinates are available. Verify anonymous retrieval and independent consumers afterward. Those workflows completed the original alpha procedure; credential lifetime and candidate-specific checks must be verified again for a new authorized release.

Container publication remains under its independent [candidate-specific license/source and fidelity gate](container-releases.md); no approval of Maven preparation waives that gate. Each release also requires the separately authorized website/download synchronization described in the container release guide.
