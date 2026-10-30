# Maven Central release preparation

**Current routing:** only stable `X.Y.Z` versions may mutate Maven Central. New alphas, betas and RCs publish to Nexus; snapshots publish after green develop pushes and also support manual dispatch. See [Nexus publication and setup](nexus-publishing.md). Previously published Central artifacts and their read-only verification remain available. Central-specific procedures below apply only to stable releases.

For normal releases from `0.3.0-alpha.2`, follow the [single-final-approval release procedure](release-process.md). The workflows and repeated qualification/review steps below are legacy recovery instructions; they are not additional gates for the unified workflow. Original source/license, identity and immutability obligations remain in force.

Target namespace: `dev.nuaforma`, verified by the maintainer through ownership of `nuaforma.dev`. The project website is hosted at `nuaforma.dev` through GitHub Pages. Those registrations are not artifact publication, website deployment or permission to distribute a container.

## Current state

The six libraries are independently staged to `build/library-repository`. Their POMs include coordinates, dependency versions, project/license/developer/SCM metadata; Java-17 main/source/documentation JARs contain the project license and third-party guide. Documentation JARs contain module guides, not generated Kotlin API pages. [Independent Maven/Gradle examples](../examples/embedded/README.md) verify actual use without source-project substitution, and runtime/POM checks guard optional dependencies.

The native source publication set contains six libraries. Use the parameterized upload and publish workflows with an explicit new version, signed source tag and exact review records. Preparation does not upload; Portal staging is `USER_MANAGED` and publication is a separate authorized action. Never retry an uncertain upload or publish automatically. See [Central's artifact requirements](https://central.sonatype.org/publish/requirements/).

The manually dispatched **Prepare Maven Central release** workflow is limited to `NuaForma/NuaForma` on `main`, protected by the `maven-central` environment. It builds, verifies standalone consumers, stages/signs the library artifacts locally and independently verifies the expected OpenPGP identity. It uploads only non-executable hash/verification records, never JARs, private keys, caches or a bundle. It does not create a release/tag, upload to Central, or use the Central publication credentials. Actual token validity is therefore not claimed by preparation.

## Maintainer configuration

The `maven-central` environment must require reviewer `mrhoeve`, disallow administrator bypass, allow only the exact `main` branch and allow self-review while there is one human maintainer. The helper verifies that actual configuration rather than relying on an environment name in YAML. Changes to the reviewed configuration require an explicit policy update.

Organization secrets (repository access granted to NuaForma/NuaForma and the maintained image-comparison repository):

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

The latter generates a disposable, passphrase-protected synthetic identity in a private temporary GnuPG home, runs `stageLibraries -PsignArtifacts=true`, verifies all twenty-four required artifact signatures with a public-only verifier, checks deliberate tampering/wrong identity/missing signature rejection, and removes the test key. It requires GnuPG; it neither reads the maintainer's private keyring nor contacts Central. Local metadata records identify the **synthetic** identity and are not release approval. Normal builds need no signing credentials; explicitly requested signing fails when credentials are missing.

Verify the configured signing identity and every exact artifact signature for each candidate. A previous key or consumer check is not approval of a new artifact, proof of current Central credentials or authorization to publish.

## Before each new publication

The current source runtime contract is `0.5` (template syntax context); published alpha.2 remains unchanged. Upload/publish workflows require server and Playground approval records tied to the same signed source/version. The server record must approve the library audit and bind every POM/main/source/documentation artifact for all six modules through `libraryHashes`. Publication requires a validated deployment and checks all staged bytes before one publish request. If the maintainer has already published in the Portal, inspect and verify public hashes instead of submitting another request.

1. Use the reviewed, signed source on `main`; run the protected preparation job with the actual signing secret and retain accepted review records. Independent local consumers and disposable-key signing QA do not replace this actual-key check.
2. Recheck **all Markdown files** against the exact candidate: implemented features, planned work, options/examples, limitations, selected coordinates and download instructions must match what is being released.
3. Select a new, unused immutable version explicitly; update module/provider metadata consistently and qualify artifacts from the signed release commit. Check current advisory/licensing findings and retain complete artifact/dependency notices. Re-run independent consumer and signature checks for that exact version.
4. Prepare the separately approved, protected publisher for that new candidate, with Central Portal credentials, an explicit manual publish decision, compliant bundle/checksums and failure/retry policy. Upload/staging at Central is an external distribution action, not a harmless local dry run; no such endpoint is called by this preparation.
5. Verify released artifacts can be retrieved through normal Maven/Gradle coordinates, with correct signatures, sources and documentation, before advertising availability.

The local `prepare_central_bundle.py` helper assembles the six modules' verified POM/main/source/documentation files, signatures and checksums in Maven layout. It rejects snapshots and existing output bundles, never uploads, and does not replace real-key verification for the exact candidate. See [Sonatype's bundle layout](https://central.sonatype.org/publish/publish-portal-upload/).

Use the parameterized **Upload reviewed release to Central** and **Publish validated release on Central** workflows. Upload checks the signed tag and exact approval records before loading secrets, signs/verifies the candidate and stages once in `USER_MANAGED` mode. Publish requires the validated deployment UUID, verifies every staged artifact hash and submits one publication request. Reject uncertain outcomes rather than retrying automatically. Verify `PUBLISHED`, anonymous retrieval and independent consumers before advertising availability.

The final bundle comparison runs from the reviewed publication policy on `main`, preserved before checking out the immutable source tag. This allows publication-only verification fixes without moving the source tag or changing candidate artifacts; every artifact name and hash must still match the approved six-library set.

Container publication remains under its independent [candidate-specific license/source and fidelity gate](container-releases.md); no approval of Maven preparation waives that gate. Each release also requires the separately authorized website/download synchronization described in the container release guide.
