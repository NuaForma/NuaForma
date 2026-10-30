# Maven development repositories

Stable versions (`X.Y.Z`) publish to **Maven Central**. Immutable development versions (`X.Y.Z-alpha.N`, beta, RC and other SemVer prereleases) publish to **Nexus prereleases**. Canonical `X.Y.Z-SNAPSHOT` versions publish to **Nexus snapshots**. Existing artifacts already on Central remain available there; they are not migrated or overwritten.

| Version | Destination | Publication behavior |
| --- | --- | --- |
| `1.2.3` | `https://repo.maven.apache.org/maven2/` | Existing Central validation and publication gates |
| `1.2.3-alpha.1`, `1.2.3-beta.1`, `1.2.3-rc.1` | `https://repo.nuaforma.dev/repository/nuaforma-prereleases/` | Immutable, immediately public after upload |
| `1.2.3-SNAPSHOT` | `https://repo.nuaforma.dev/repository/nuaforma-snapshots/` | Native Maven timestamp/build-number generations |

There is no fallback to Central when Nexus fails. Invalid versions, noncanonical snapshot spellings, build metadata and path fragments fail closed. Central upload/publish helpers reject every non-stable version, including when invoked directly or through an older workflow. Read-only verification of previously published Central alphas remains possible.

## Nexus operator setup

Create two **maven2 (hosted)** repositories. Keep **Online** enabled, **Layout policy: Strict**, and use the intended blob store:

- `nuaforma-prereleases`: **Version policy: Release**, **Deployment policy: Disable redeploy**.
- `nuaforma-snapshots`: **Version policy: Snapshot**, **Deployment policy: Allow redeploy** (Maven metadata changes for new timestamped generations).

For each repository, grant the dedicated publisher only the corresponding `nx-repository-view-maven2-<repository>-browse`, `-read`, `-add`, and `-edit` privileges. `edit` supports Maven PUT deployment and metadata updates; Nexus's deployment policy still refuses replacing immutable release artifacts. Repository administration and delete privileges are unnecessary. Enable anonymous access with only the matching **browse/read** privileges; give anonymous users no upload rights. Verify these policies in the Nexus UI before the first real publication. Anonymous HTTP checks cannot prove authenticated deployment policy.

Use organization secrets **`NEXUS_USERNAME`** and **`NEXUS_PASSWORD`**, scoped to `NuaForma/NuaForma` and `NuaForma/image-comparison`. Continue using **`MAVEN_SIGNING_KEY`** and **`MAVEN_SIGNING_PASSWORD`** for both destinations. Stable Central operations keep **`MAVEN_CENTRAL_USERNAME`** and **`MAVEN_CENTRAL_PASSWORD`**. Resolve unintended repository/environment overrides by name. Secret values stay in 1Password and process/step environments; do not put credentials in repository URLs, Gradle properties, workflow inputs, files, command arguments or logs. Signing/publication commands disable the configuration cache.

## Automatic and manual snapshots

Every push to develop automatically publishes all six signed libraries after its own Java 17/25 jobs and actual SonarCloud analysis/Quality Gate succeed. The publisher verifies the exact signed source and current Build run; it allows up to two minutes for GitHub job-completion metadata to propagate and up to ten minutes for the real Sonar check, never substitutes a generic status and never publishes from PRs, main or forks. Builds and publications are queued without replacing pending source revisions. A prerelease such as `0.3.0-alpha.5` produces `0.3.0-SNAPSHOT`; a stable `0.3.0` baseline starts `0.3.1-SNAPSHOT`. Source versions remain unchanged, while Maven creates a timestamped generation for each publication.

Manual dispatch remains available. Wait for the selected **develop commit** to have successful Java 17/25 CI (and the actual SonarCloud analysis for NuaForma). In Actions, select **Publish signed snapshot to Nexus**, choose **develop**, enter a canonical snapshot version, and type **`PUBLISH_SNAPSHOT`**. The workflow checks the exact GitHub-verified signed checkout and latest required check runs before loading any publishing secrets. A generic SonarCloud status or an older green build cannot substitute for the required checks.

Gradle's native `maven-publish` handles timestamp/build-number metadata; do not PUT non-timestamped snapshot filenames using the immutable prerelease uploader. Remote snapshot tasks are registered only with explicit `-PpublishSnapshots=true`, accept only canonical snapshot versions and refuse unsigned publication. Ordinary builds and local staging do not add any remote publishing target. The workflow compares anonymously downloaded POM/main/source/documentation JARs, Gradle module metadata when present, **and signatures** with the locally signed artifacts. Snapshot publication creates no release tag, GitHub release, container image or site deployment. Automatic calls share the same gated, signed publisher with manual dispatch; uploads are serialized without cancelling an in-progress or pending publication.

## Immutable prerelease integrity and recovery

The prerelease route reuses the reviewed Maven-layout bundle and signature validation; it does not rebuild or re-sign after approval. The uploader checks the reviewed bundle hash, every artifact hash, required detached signatures and checksums; rejects unexpected/duplicate entries; checks every target path and existing module index before writing; then uploads the same bytes. It maintains module `maven-metadata.xml` and its checksums, preserves earlier versions and uses available metadata ETags as conflict guards. Existing index entries also refuse a repeated version.

Only trusted fixed HTTPS Nexus URLs receive authentication. Redirects are rejected, no write is automatically retried by the prerelease uploader, and anonymous readback verifies every artifact, signature, checksum and module index. A JSON journal records successful paths and the pending path **before** each write, with no credentials. A success response alone is not proof of public byte identity.

Nexus PUT publication is **not an atomic multi-artifact transaction**. If it fails partway through, keep the receipt and inspect the pending path and repository state; do not rerun the whole workflow or delete/overwrite a release version to hide a partial upload. Resume only a separately reviewed missing operation using the original approved bytes, or select a new prerelease version and obtain its normal approval. Snapshot generations are deliberately mutable development coordinates; their workflow also performs no whole-job automatic retry.

## Anonymous consumers

Stable users continue to use Maven Central. Add the following repository when opting into a **new Nexus prerelease**; keep dependency versions explicit. No consumer credentials are needed.

```xml
<repositories>
  <repository>
    <id>nuaforma-prereleases</id>
    <url>https://repo.nuaforma.dev/repository/nuaforma-prereleases/</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>
```

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "nuaformaPrereleases"
        url = uri("https://repo.nuaforma.dev/repository/nuaforma-prereleases/")
        content { includeGroup("dev.nuaforma") }
        mavenContent { releasesOnly() }
    }
}
```

For snapshots add the separate `nuaforma-snapshots` URL, enable snapshots and disable releases in Maven, or use `mavenContent { snapshotsOnly() }` in Gradle. Avoid dynamic versions in reproducible builds: snapshots may resolve to a newer generation later. Static checks and local staging do not prove repository write permissions. Every actual publication must retain its verification receipt and independently compare anonymous artifact/signature bytes; immutable prereleases must remain non-redeployable.

The Maven client maintains repository metadata ([Sonatype documentation](https://help.sonatype.com/en/tasks.html)); Gradle supports Nexus and native snapshot publishing through its existing [Maven Publish plugin](https://docs.gradle.org/current/userguide/publishing_maven.html). No runtime dependency is added by this routing.
