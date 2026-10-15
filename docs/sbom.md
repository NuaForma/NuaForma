# Shipped-component SBOM

`GET /api/v1/sbom` downloads a build-generated CycloneDX **1.7 JSON** attachment (`application/vnd.cyclonedx+json`, filename `nuaforma-sbom.json`). It retains `X-Request-ID`, `nosniff`, `Cache-Control: no-store` and an SHA-256 `ETag`. Normal request admission/deadline limits apply. There is no runtime scan, network upload, converter invocation or private-resource enumeration. Restart to change the immutable startup snapshot; repeated requests return the same bytes.

## Scope and identity

- A standalone server describes its resolved JVM production dependency graph and available POM license evidence. The separate standalone Playground adds its application component and the exact server JAR. Test/build dependencies and the CycloneDX generator/validator are not runtime components.
- A source-built service container additionally describes the actual application JARs, isolated PDFBox/veraPDF JARs, both pinned Java installations, Bubblewrap, the compiled renderer filter and the outer/renderer Debian package databases. Package records retain installed/source versions and actual package architectures, including architecture-independent packages. Java release-file hashes are explicitly properties, not mislabeled full-runtime artifact hashes. Native inventory is package-level rather than exhaustive per-file provenance.
- Application versions and source commits are build identities, not proof of an approved release or a clean checkout. Internal build paths, transient timestamps and machine/CI environment metadata are removed. Components have package URLs where their identity supports one; local container/helper/Java roles use distinct BOM references without inventing an OCI digest before image construction.
- The `nuaforma:inventory:scope` and container architecture metadata distinguish these inventories. `compositions.aggregate` is deliberately `incomplete`: do not claim universal completeness, host-kernel inventory, vulnerability freedom, license compliance or complete build provenance.
- Operator-installed extensions, mounted/private fonts, request uploads, business data, credentials and host files are excluded. OS-distributed fonts remain represented by their shipped package records. Installing a private extension does not make it publicly listed through this endpoint.

Missing license evidence is left absent, not guessed from the project license. The existing license, notices, matching source and redistribution audit remains mandatory; an SBOM does not replace it. NuaForma remains Apache-2.0, while bundled components retain their own terms.

## Build and verification

The approved Apache-2.0 CycloneDX Gradle plugin **3.5.1** runs only in the build. It consumes `runtimeClasspath`; normalization reconciles the generated graph with actual resolved artifact files and calculates SHA-256 hashes. Packaging fails if production metadata is missing. The generated standalone JSON is packaged in the server JAR; the Playground has a separately composed inventory. No dependency is added to embedded libraries or the service for SBOM generation/validation.

```sh
./gradlew :nuaforma-server:generateSbom :nuaforma-server:verifyShippedSboms
./gradlew :nuaforma-server:validateSbomFile -PsbomFile=build/candidate-sbom.json
```

The approved build tool's bundled official CycloneDX 1.7 JSON Schema validator independently checks the normalized final documents, including a deliberately invalid negative control. The schema validator is build-only. Container inventories are assembled from the final source-target inputs, then extracted and schema-validated during native qualification on AMD64/ARM64. Inventory checks fail on omitted or stale application JAR hashes. HTTP, snapshot, scope, graph and metadata-removal regressions are separate tests.

`NUAFORMA_SBOM_PATH` is trusted operator configuration for a prevalidated absolute regular file, loaded once with a 16 MiB ceiling. Source container targets set it to their bundled inventory. It is never a request option or a user-uploaded path. Operators replacing it are responsible for supplying a truthful, validated document; the runtime does not perform a live rebuild or comprehensive JSON Schema validation.

Playground impact: its SBOM download link uses the same endpoint and describes its own shipped distribution, not the connected browser, operator fonts or user uploads. The endpoint is available in `0.2.0-alpha.1`; each future release still requires aligned production/Playground qualification and website synchronization. Building an inventory does not publish an image.
