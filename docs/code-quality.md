# Code quality and coverage

[Issue #94](https://github.com/NuaForma/NuaForma/issues/94) integrates the public [NuaForma SonarQube Cloud project](https://sonarcloud.io/dashboard?id=NuaForma_NuaForma&branch=develop) with organization `nuaforma`, project key `NuaForma_NuaForma` and the OSS plan. Repository configuration alone does not prove a successful authenticated upload; verify the first CI analysis before closing the issue.

## Local coverage without a token

```sh
./gradlew coverageReport -Pcoverage=true
python3 tools/quality/check_coverage.py
python3 -m unittest discover -s tools/quality -p 'test_*.py'
```

The root report is `build/reports/jacoco/coverageReport/coverageReport.xml`; open the adjacent `html/index.html` for a browsable report. The property accepts only `true` or `false` and defaults to `false`, so ordinary builds do not attach a coverage agent. Coverage-enabled tests run on the same Java 17 library and Java 25 server/Playground toolchains as normal tests. Switching the property invalidates test results rather than silently reusing uninstrumented runs.

The report merges execution data from all existing module JVM test suites against all seven modules' actual production class/source directories. Server integration tests can therefore contribute to library coverage without duplicating classes or treating test fixtures as product code. The Playground currently has no JVM test classes: its production classes remain measured, including uncovered lines, without fabricating an execution file or treating its JavaScript tests as JVM coverage. Uncovered production classes remain in the report. The scanner imports the same XML in each module, resolving entries against that module's own sources. There are no blanket coverage exclusions or new numerical thresholds.

JaCoCo measures JVM execution in those Gradle test processes, not browser JavaScript, Python helpers, independent consumers or sandboxed renderer/validator child JVMs. Do not propagate the test agent into isolated children, change sandbox rules or interpret unmeasured child execution as a failed runtime test. Run Playground JavaScript checks separately. Coverage percentages and static findings are useful signals, not proof of security, behavioral correctness or document fidelity.

## GitHub Actions

Pull requests targeting `develop`/`main` run the Java 17/25 matrix. Push builds are restricted to those two integration/release branches, so feature updates do not run the same matrix twice via both push and PR events. Merged changes still receive a branch build and branch analysis; manual dispatch remains available. Both product branches require `Build (Java 17)` and `Build (Java 25)`, with `Release promotion` additionally required on main. The separate native AMD64/ARM64 checks and other protection rules are unchanged.

The existing Build workflow keeps its Java 17/25 matrix. Only the Java 25 build records and verifies the aggregate report; analysis runs after successful checks. The scanner step explicitly excludes test execution and report regeneration (`-x test -x coverageReport`), consuming the report verified in the preceding build rather than rerunning tests with the token present. Checkout retains full Git history without persisted credentials. Sonar receives public project source and analysis metadata; never run it on customer templates, private extensions or production data.

The scanner runs for pushes/manual runs on `develop` or `main`, and for opened/reopened/updated PRs targeting those branches when both repositories are `NuaForma/NuaForma`. Fork and Dependabot PRs still build/test but skip authenticated analysis. The workflow never uses `pull_request_target` or privileged artifact handoffs. `SONAR_TOKEN` is provided only to the scanner step, not to compilation/tests; eligible analysis fails clearly if it is absent. Same-repository code and workflow changes remain trusted maintainer input and can execute during Gradle scanning; review them accordingly.

The scanner does not wait for or override the server-side Quality Gate. The README badge shows the current `develop` Quality Gate, which can lag a recent push while Sonar processes the report. Requiring both Java builds does not add a mandatory Sonar gate or change its quality profiles/issue dispositions. Do not lower a gate or suppress findings merely to get a green badge. The project does not yet import browser/Python coverage.

## Reviewed dependency verification

The source build uses Gradle's strict SHA-256 dependency verification, independently of Sonar or coverage. [Verification metadata](../gradle/verification-metadata.xml) pins individual artifacts and their POM/Gradle module metadata, including plugins, compiler, build/test tools, application dependencies and isolated validator libraries. Unknown artifacts or mismatched bytes fail resolution. Dependency locks still select versions; checksums additionally check the selected bytes. There are no wildcard trust rules or ignored artifacts.

[Review receipts](../gradle/verification-review.json) record the SHA-256, size and exact official repository URL for every accepted artifact. The review helper freshly downloads bytes over HTTPS independently of Gradle's local cache, compares them with the candidate metadata and writes receipts only after every comparison succeeds. Plugin markers use the configured Gradle Plugin Portal; ordinary artifacts prefer Maven Central, with the official Plugin Portal as a fallback for portal-only artifacts. Its official artifact CDN is allowed. The Kotlin JVM marker POM on Central contains additional descriptive metadata; only the Portal bytes actually used by this build are accepted.

This is repository-byte integrity pinning, **not publisher-signature verification**, a vulnerability scan or proof that upstream code is safe. A compromised upstream repository or an already malicious initial version is outside what same-origin HTTPS/checksum comparison proves. Review dependency identity, licenses, release history and security separately. The Gradle Wrapper retains its separate pinned checksum; OS packages/native renderer artifacts and scanner-provisioned analysis engines are outside Gradle's dependency inventory and retain their own review mechanisms.

For a deliberate dependency update, on a trusted workstation:

```sh
./gradlew --write-verification-metadata sha256 help
python3 tools/quality/dependency_verification.py --review
./gradlew verifyDependencyVerification verifyQualityTooling
python3 tools/quality/probe_dependency_verification.py
```

Bootstrapping is only a candidate, never automatic approval: review the XML/receipt diff and compare important dependencies with upstream release evidence. Resolve any task-specific inputs needed by the affected build; qualify both Java toolchains and native architectures in CI. Do not append a mismatched hash, add a trust exemption, disable verification or generate acceptance metadata in CI merely to make a failure pass. Commit the reviewed XML and receipts together. New/mismatched/unreviewed entries fail the offline review guard in the normal build; CI also exercises Gradle itself with a synthetic private Maven fixture, accepting reviewed bytes and rejecting altered or unlisted artifacts without changing the real dependency cache.

For read-only checks of existing review records, omit `--review`; normal builds do not redownload all artifacts for this helper or rewrite either file. See [Gradle dependency verification](https://docs.gradle.org/9.7.0/userguide/dependency_verification.html) for its scope and bootstrap limitations. Playground impact: build integrity only; no UI, request, runtime dependency or container behavior changes.

## Maintainer setup and token rotation

Before the first CI-based upload:

1. In the NuaForma Sonar project, open **Administration → Analysis Method** and turn off **Automatic Analysis**. Automatic and CI-based analysis must not run together. Retain the GitHub binding and OSS plan. Check that the project's main branch is `develop`, matching GitHub's default working branch.
2. In SonarQube Cloud, open **My account → Access Tokens → Personal Tokens**. Generate a dedicated token named, for example, `nuaforma-github-actions`, using an account with **Execute Analysis** permission on this project. Personal tokens inherit the account's permissions; their name does not limit their scope. Scoped organization tokens are currently documented for Team/Enterprise, not the OSS plan; do not upgrade the subscription for this integration.
3. Choose an explicit expiry and record a rotation reminder; a custom six-month lifetime is supported within Sonar's current one-year maximum. Save the token securely in 1Password and keep 1Password as the source of truth. Do not paste it into chat, repository files, a command argument, shell history or a local `.env` file.
4. Open the GitHub repository's **Settings → Secrets and variables → Actions → New repository secret**. Set the name to **`SONAR_TOKEN`** and paste the token directly into the secret value. Use a repository secret, not a public variable or either protected release environment. No Maven/GHCR secret or permission changes are needed.
5. After authorized delivery, run Build for `develop`, or use the eligible PR build. Confirm the scanner succeeds, all seven modules resolve, the JaCoCo XML is imported and the analysis appears for the correct branch/PR. A local dry run is not evidence that Sonar accepted coverage.

For rotation, generate a replacement, update this one GitHub secret, verify a scan, then revoke the old token and update 1Password. If compromise is suspected, revoke immediately rather than waiting for a scan. Authentication failures or expiry should be repaired, not bypassed. Token creation and secret entry are maintainer actions; no token value is needed by the assistant.

## Tooling and scope

SonarScanner for Gradle 7.5.0.8588 is LGPL-3.0 and JaCoCo 0.8.15 is EPL-2.0. The approved tools are unmodified build/test dependencies only, excluded from published libraries, service/Playground distributions and shipped SBOM runtime inventories. Preserve their upstream licenses/notices and applicable source obligations if redistributing the tooling itself. See [dependency review](../DEPENDENCIES.md) and [third-party notices](../THIRD_PARTY.md). No application, contract, Playground UI, renderer or validation behavior changes are introduced.

Primary setup references: [SonarScanner for Gradle](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/scanners/sonarscanner-for-gradle), [personal tokens](https://docs.sonarsource.com/sonarqube-cloud/managing-your-account/managing-tokens) and [scoped organization token availability](https://docs.sonarsource.com/sonarqube-cloud/administering-sonarcloud/managing-organization/scoped-organization-tokens).
