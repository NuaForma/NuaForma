# NuaForma — complete starter kit

For NuaForma 0.2.0-alpha.1. Libraries use Java 17; the production service and separate development Playground use Java 25. This ZIP contains instructions/examples, not application binaries or a hosted generation service.

```text
Deploy/
  AMD64/               Production Compose/Dockerfile for Intel/AMD
  ARM64/               Production Compose/Dockerfile for Apple Silicon/ARM64
  Playground/AMD64/    Development Playground for Intel/AMD
  Playground/ARM64/    Development Playground for Apple Silicon/ARM64
  Helm/                Kubernetes starting-point chart with lifecycle probes
Collections/
  Bruno/               Open the folder containing bruno.json
  Postman/             Import NuaForma.postman_collection.json
Testkit/               Word template, seven JSON requests and EXAMPLES.md
```

1. Choose a deployment, read its README and retain its reviewed security settings. Compose requires the included `seccomp.json`; extract the entire folder. Create `custom-fonts` and `extensions` folders. Podman/macOS needs its documented provider and VM-visible absolute paths. Do not use privileged or unconfined execution. Helm is a starting point, not a qualified Kubernetes/OpenShift platform.
2. Check `/api/v1/capabilities`: PDF must appear under the provider's `available.writeFormats`. `/health/ready` alone does not establish PDF readiness. `/api/v1/version` identifies the build; `/api/v1/sbom` downloads its shipped-component inventory.
3. Open Bruno's collection folder, or import the Postman collection and select its extracted folder as the working directory. Leave unrestricted file access off. Set `baseUrl` if needed (default `http://localhost:8080`). Each collection contains 12 requests: version, capabilities, assessment, OpenAPI JSON/YAML and seven generation examples.
4. Alternatively follow `Testkit/EXAMPLES.md` with curl. Save successful PDF/DOCX responses as documents; error responses are JSON. Allow at least 180 seconds for PDF. The seventh example supplies a Base64 image via the image content pipe; the others omit it with null.

The identical copies under `Collections/*/assets/` and `Testkit/` are snapshots; editing one copy does not update the others. No credentials, private fonts, client binaries or automatic file-saving scripts are included.

**Playground is development-only.** It enables sensitive development logging by default and exposes `/playground/`; use synthetic data, not production documents. Start either production or Playground on host port 8080, not both on that same port. Operators remain responsible for network reachability and access controls.

The evaluation alpha retains documented native-runtime security risk accepted for this release; remediation remains open in [issue #51](https://github.com/NuaForma/NuaForma/issues/51). No authentication/TLS is built in. Use an authenticated TLS gateway before remote exposure; retain isolation and quotas. Installed extensions are trusted executable operator code.

See the [documentation index](https://github.com/NuaForma/NuaForma/blob/main/docs/README.md) and matching release notes. Review client workspace/cloud-sync settings before adding confidential data. NuaForma-authored examples are Apache-2.0; bundled component notices and terms remain applicable. Branding grants no trademark rights.
