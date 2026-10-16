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

The existing Build workflow keeps its Java 17/25 matrix. Only the Java 25 build records and verifies the aggregate report; analysis runs after successful checks. The scanner step explicitly excludes test execution and report regeneration (`-x test -x coverageReport`), consuming the report verified in the preceding build rather than rerunning tests with the token present. Checkout retains full Git history without persisted credentials. Sonar receives public project source and analysis metadata; never run it on customer templates, private extensions or production data.

The scanner runs for pushes/manual runs on `develop` or `main`, and for opened/reopened/updated PRs targeting those branches when both repositories are `NuaForma/NuaForma`. Fork and Dependabot PRs still build/test but skip authenticated analysis. The workflow never uses `pull_request_target` or privileged artifact handoffs. `SONAR_TOKEN` is provided only to the scanner step, not to compilation/tests; eligible analysis fails clearly if it is absent. Same-repository code and workflow changes remain trusted maintainer input and can execute during Gradle scanning; review them accordingly.

The scanner does not wait for or override the server-side Quality Gate. The README badge shows the current `develop` Quality Gate, which can lag a recent push while Sonar processes the report. Existing required checks, quality profiles, issue dispositions and branch protections are unchanged. Do not lower a gate or suppress findings merely to get a green badge. The project does not yet import browser/Python coverage.

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
