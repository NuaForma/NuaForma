# Endpoint and Playground integration (unreleased source)

Issues #60, #80, #48 and #78 are delivered together with one signed issue commit each and one combined pull request. Publication is excluded; existing alpha artifacts and website downloads remain unchanged. The maintainer reviewed and approved the local Playground before final delivery.

## Pipe capabilities (#60)

The additive provider-owned pipe catalogs reuse the public optional metadata facets. They preserve independent embedded use, distinguish absent/invalid/empty metadata and declared versus currently usable content combinations, and document non-chainability separately from typed value transformations. See the [server guide](../nuaforma-server/README.md#pipe-capabilities-unreleased-source).

Playground impact: populate pipe help and supported contexts/profile hints from the selected provider's catalog. Never assume that installed or declared support is executable. Show catalog diagnostics without interpreting exception messages or requiring the user to know implementation classes.

## Health (#80)

Source liveness/readiness and Docker/Kubernetes probes are implemented; see the [health guide](health.md). Readiness uses a background snapshot rather than invoking converters from each health request. Targeted lifecycle, capacity and HTTP contract tests pass; final native-image qualification remains part of the combined delivery.

## Static shipped-component SBOM (#48)

Approved route: `GET /api/v1/sbom`, serving build-generated CycloneDX 1.7 JSON with `application/vnd.cyclonedx+json`. The official Apache-2.0 CycloneDX Gradle plugin 3.5.1 is approved only as a build tool. The standalone server describes its JVM production runtime; the container inventory also includes the actual bundled renderer, validators, Java and OS components. No runtime scanning, uploads or private font/data/extension inventory. The SBOM is not a vulnerability or license/source audit.

## Playground (#78)

The separate Java 25 Gradle application and `playground` image target reuse capabilities/OpenAPI/health/SBOM and the same generation API. Production has no GUI module/assets. The interface supports automatic/explicit provider selection, typed options, nested data, requested watermarks, available fonts and private font uploads, separate resources, request/curl exports and actual generation. Preserve warnings, operator-controlled reachability, no-default-persistence and mandatory future impact review. GUI routes/assets stay outside both OpenAPI documents even while enabled. See the [Playground guide](../nuaforma-playground/README.md).

Playground impact examples: #60 updates metadata-driven pipe help; #80 reuses server lifecycle/probes without an extra engine; #48 adds the static inventory link and a separate inventory for its distribution. A pure internal refactor with unchanged API/capabilities/behavior can record no GUI change, with an explanation and retained regression checks.
