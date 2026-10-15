# NuaForma — complete starter kit

Container examples target `0.1.0-alpha.3`. This archive contains configuration and synthetic examples, not container images or client binaries. An internet connection is needed to pull the image; document generation runs on your installation.

## Documentation and version scope

Use the [documentation index](https://github.com/NuaForma/NuaForma/blob/main/docs/README.md), [build guide](https://github.com/NuaForma/NuaForma/blob/main/docs/development.md) or [extension guide](https://github.com/NuaForma/NuaForma/blob/main/docs/extensions.md) for deeper instructions. Repository `main` also describes newer source features. This kit deliberately retains alpha.3-compatible requests, Java 17 extensions and deployment settings: health/SBOM endpoints, value/content pipes, image insertion and the separate Playground require a matching newer/source build. No new release is supplied by these documentation changes.

## Contents

```text
Deploy/
  AMD64/               Compose and Dockerfile for Intel/AMD
  ARM64/               Compose and Dockerfile for Apple Silicon/ARM64
  Helm/                Kubernetes starting-point chart
Collections/
  Bruno/               Open the folder containing bruno.json
  Postman/             Import NuaForma.postman_collection.json
Testkit/               Word template, six JSON requests and EXAMPLES.md
```

1. Choose one deployment under `Deploy/`, read its README and retain the reviewed security settings. Use the entire directory: Compose requires `seccomp.json` alongside it. For Podman/macOS, follow the ARM64 README's provider and absolute-path instructions. Do not use privileged or unconfined execution. Helm is a starting point, not a qualified Kubernetes platform.
2. Check `GET /api/v1/capabilities` on your service. The PDF provider must list `pdf` under `available.writeFormats`; HTTP success or a declared PDF format alone is not enough.
3. Open one collection and follow its README. Each has its own `assets/` with the same template and six JSON files, so relative references work after extraction. Postman requires selecting `Collections/Postman/` as its working directory; do not enable unrestricted file access. Set `baseUrl` when needed (default `http://localhost:8080`).
4. Alternatively follow `Testkit/EXAMPLES.md` using curl from `Testkit/`. Save successful responses as PDF or DOCX (scenario 05). Errors are JSON diagnostics, not documents. Allow at least 180 seconds for PDF requests.

The copies under `Collections/*/assets/` and `Testkit/` are identical when packaged; editing one copy later does not update the others. Collections have 11 requests, including all six generation examples, version, capabilities, assessment and OpenAPI JSON/YAML. No filesystem scripts, automatic binary saving, tokens or private fonts are supplied.

This is an evaluation alpha. The service has no built-in authentication/TLS; keep local access on loopback or use an authenticated TLS gateway. Installed extensions are trusted operator code. Review release notes and accepted native-runtime findings in https://github.com/NuaForma/NuaForma/issues/51. Review client workspace/cloud-sync settings before adding confidential data. NuaForma-authored samples are Apache-2.0; deployment notices/licenses remain included. Branding grants no trademark rights.
