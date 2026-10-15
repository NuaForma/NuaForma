# Native ARM64 containers

Issue [#63](https://github.com/NuaForma/NuaForma/issues/63) added native Linux/arm64 builds alongside Linux/amd64. Published `0.1.0-alpha.3` includes both architectures on Java 17; the older amd64-only `0.1.0-alpha.1` stays immutable. The source Java 25 server and Playground passed fresh native AMD64/ARM64 checks in [PR #81](https://github.com/NuaForma/NuaForma/pull/81); each changed release candidate still requires its own qualification and does not modify those published tags or the website's alpha.3 deployment downloads. Native hosted qualification alone is not evidence of a newly changed runtime on Apple Silicon/Podman.

## Why emulation is insufficient

QEMU user-mode translation cannot execute the namespace-bearing `clone()` needed by Bubblewrap. A native ARM64 VM supporting user namespaces does not make an emulated amd64 sandbox work. Loosening seccomp, granting host capabilities or using setuid/unconfined execution is not an accepted workaround. A native ARM64 image needs native LibreOffice, Java and Bubblewrap and an AArch64-aware internal BPF filter.

The Debian and Java 17 validator indexes retain their previously qualified AMD64/ARM64 children. The source service target adds a separately pinned Temurin 25.0.4.1+1 server runtime on both architectures; validator Java remains 17.0.20.1+1. See [the exact runtime inputs and compatibility boundary](../../docs/java-runtime-compatibility.md). LibreOffice remains 26.8.1.1; the official ARM64 archive is pinned to SHA-256 `1b069c20dd237f6decad3ea02cb02f39fdf458b03d09c2a9facb7b9b71e6a27a`. The amd64 archive checksum is unchanged. Building under emulation is allowed for compilation checks only, never native runtime qualification.

## Local candidate build

Install JDK 17 and JDK 25, then stage Java-17-compatible library/validator and Java 25 server inputs first:

```sh
./gradlew build :nuaforma-server:installDist :nuaforma-docx-pdf:preparePdfValidation :nuaforma-docx-pdf:prepareVeraPdfValidation
python3 tools/container/prepare_verapdf_materials.py nuaforma-docx-pdf/build/verapdf-validation
python3 tools/container/prepare_application_materials.py
docker build --platform linux/arm64 --file deployment/pdf-runtime/Dockerfile \
  --build-context pdfvalidation=nuaforma-docx-pdf/build/pdf-validation \
  --build-context verapdfvalidation=nuaforma-docx-pdf/build/verapdf-validation \
  --build-context application=nuaforma-server/build/install/nuaforma-server \
  --target service --tag nuaforma:arm64-candidate deployment/pdf-runtime
```

BuildKit's automatic `TARGETARCH` selects pinned inputs; unsupported architectures fail rather than fall back to amd64. Podman users need a BuildKit-compatible builder for these named-context commands; do not assume every `podman build` version supports this syntax. Build support does not authorize transferring a candidate binary before its source/license provision is complete.

## Podman runtime

After acquiring an audited ARM64 candidate, configure the source Compose file using administrator-selected values:

```sh
NUAFORMA_IMAGE=nuaforma:arm64-candidate \
NUAFORMA_PLATFORM=linux/arm64 \
NUAFORMA_SECCOMP_PROFILE=/absolute/path/visible/in/the/podman-vm/seccomp.json \
PODMAN_COMPOSE_PROVIDER="$(command -v podman-compose)" \
podman compose --file deployment/pdf-runtime/compose.yaml up -d --no-build
```

Use absolute VM-visible paths for fonts/extensions through `NUAFORMA_CUSTOM_FONTS_DIR` and `NUAFORMA_EXTENSIONS_DIR` where necessary. The standalone `podman-compose` provider avoids the Docker-compatible API inline-seccomp bug in Podman 6.1.1/6.1.3. Validate exact runtime versions; do not claim all Podman releases/providers work. All existing user, read-only, init, capability, resource and seccomp settings remain required.

## Qualification and release gate

The preparation and pull-request workflows use separate native amd64 and ARM64 runners. `verify_native_platform.py` compares candidate architecture to the Linux engine and rejects cross-architecture runtime qualification. Both workflows check the actual BPF bytes, run thirty-two synthetic probes through the candidate's own Bubblewrap/filter and exercise real DOCX/PDF/PDF-A generation, cancellation and cleanup. The proc-free launcher retains the private PID namespace and verifies its identity, child creation, cleared capabilities and active seccomp through kernel syscalls, plus absence of procfs paths and inherited procfs descriptors. The headless device boundary adds seven checks without exposing pseudo-terminals. It never removes OCI masks, exposes the application's procfs or disables PID isolation. The probe is built in a separate `sandbox-probe-build` target using the `qualificationfixtures=tools/pdf-evaluation` named context; it is never installed in the service image. A compiled BPF policy check is additional evidence, not a sandbox proof.

Run the existing mount/font/concurrency, visual and independent barcode checks on each final architecture before approving its release record. The automatic base qualification alone does not establish complete fidelity or barcode scanability.

Native hosted ARM qualification is necessary but does not prove Apple Silicon/Podman integration. Repeat readiness and all website scenarios on the actual Mac/Podman setup before documenting that combination as supported. PDF remains unavailable if required isolation fails.

Collect separate exact image/config/package/JAR inventories and corresponding source/license records for both variants. Shared source archives may be deduplicated only when their identities really match. Future publication creates one versioned OCI index from both approved images; neither the old alpha tag nor its approval record is changed. Maven JARs stay architecture-independent. Prereleases never update `latest`.
