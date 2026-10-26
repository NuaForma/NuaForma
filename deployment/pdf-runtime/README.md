# Single-container PDF runtime

On the own-engine branch, this directory packages NuaForma's Java 25 application and a separate bounded Java 17 PDF validator. DOCX layout and PDF writing execute in the hosting JVM; LibreOffice is neither installed nor used as a fallback. The `qualification` target invokes only the validator, while `service` packages the actual REST entry point. This new candidate is under qualification and requires a fresh distribution audit. See the [native input profile](../../nuaforma-docx-pdf/README.md) and [on-premise service guide](SERVICE.md).

## Build and application use

Unreleased issue #93 source adds a strict default-on operator conformance policy; see [service configuration](SERVICE.md#docx-pdf-and-fonts) and [embedded configuration](../../nuaforma-docx-pdf/README.md#operator-conformance-policy-unreleased-issue-93-source). Explicit `false` skips only per-document veraPDF, never PDFBox, probe/runtime identity, network denial, private JVM/files or budgets. All validators remain bundled with the same license/source/SBOM obligations. Enabled validation still binds the requested profile to the final file digest and fails closed. Disabled output carries an export profile, not a verified-conformance claim; the operator owns downstream checking. Default-on release qualification remains mandatory. Published alpha images and digests stay immutable.

Build with JDK 17 and JDK 25 installed and the repository wrapper, then Docker with BuildKit. The library/validator toolchain remains 17; the separately packaged server uses 25:

```sh
./gradlew :nuaforma-docx-pdf:preparePdfValidation :nuaforma-docx-pdf:prepareVeraPdfValidation
python3 tools/container/prepare_verapdf_materials.py nuaforma-docx-pdf/build/verapdf-validation
docker build --platform linux/amd64 --file deployment/pdf-runtime/Dockerfile \
  --build-context pdfvalidation=nuaforma-docx-pdf/build/pdf-validation \
  --build-context verapdfvalidation=nuaforma-docx-pdf/build/verapdf-validation \
  --target qualification --tag nuaforma-pdf-runtime-qualification:11-pinned deployment/pdf-runtime
```

The named validator contexts contain only staged validator distributions and retained license/source/profile materials, never the checkout, private fonts, local instructions or secrets. The service accepts only Gradle's production `installDist` directory as its application context. Its entry point captures immutable fonts from `/usr/share/fonts` and operator mounts, checks application/JVM/validator SHA-256 inventories, and configures `nuaforma.pdf.validator.launcher=/opt/nuaforma/bin/validate-pdf`. The old renderer-launcher property no longer selects a backend. Embedded consumers can configure `NativePdfConfiguration` and `PdfProcessValidator` directly.

Runtime execution requires Docker's `--init` (or an equivalent platform-provided child reaper), UID/GID 1000, a read-only outer root, `--cap-drop ALL`, `--security-opt no-new-privileges`, the adjacent absolute-path `seccomp.json`, `--pids-limit 128`, `--memory 2g`, `--cpus 2` and a writable `/tmp` tmpfs capped at 512 MiB with mode 1777. Do not run the Java application alone as container PID 1: terminated conversion children can otherwise remain as orphaned zombie registrations after cancellation. Docker supplies its init helper; NuaForma does not bundle another init dependency. Mount only the application's explicitly needed inputs and output destinations. Never mount a Docker socket. These are qualification settings, not permission to disable host protections.

The profile retains Moby's default-deny container filter but permits the reviewed AMD64/ARM64 user-namespace setup and mount operations required by Bubblewrap. The operations remain subject to kernel user-namespace capabilities; the container receives no host `SYS_ADMIN`. Inside the namespace, `renderer-seccomp.c` denies mount/namespace reconfiguration, network socket families other than AF_UNIX, ptrace and other privileged interfaces. Normal process/thread creation remains available. This is not `seccomp=unconfined` or a privileged container.

Bubblewrap exposes a minimal immutable validator root, private `/dev` and `/tmp`, and read-only access to the current request directory. It retains private user/PID/network/IPC/UTS/mount namespaces, clears inherited environment data, and provides no procfs or LibreOffice compatibility mounts. Only validation runs here; native rendering uses bounded request-local state in the application JVM. Validation has fixed arguments and CPU/file/address-space limits. Isolation failure leaves PDF unavailable, without an unsandboxed validation fallback. The `/opt/nuaforma/renderer` filesystem contains only the minimal validator OS; application rendering does not run there.

## Components and licenses

Pinned inputs are visible in Dockerfile and `debian.sources`:

- Debian trixie-slim by immutable AMD64/ARM64 index digest, with authenticated Debian/security package snapshots at `20261012T000000Z`. Archive expiry checking is disabled only for the fixed snapshot; signature and package-hash verification remain enabled. Updating security packages requires an explicit snapshot change and renewed qualification.
- Debian Liberation fonts with their original copyright/permission notices; no Office binary or proprietary fonts are bundled.
- Unmodified Bubblewrap 0.13.0 source archive, SHA-256 `4734237473c0e5d695e4e9034a34e43b2dbf5164655bd13fa59ae376b2b7a765`, compiled with the recorded Meson flags. It is not setuid. Its LGPL-2.1-or-later `COPYING`, `COPYING.LIB` and exact corresponding source archive are retained under `/usr/share/nuaforma`.
- Temurin Java 17.0.20.1+1 validator runtime by immutable image digest, with its complete `/opt/java/openjdk/legal` directory. The `service` target separately adds Temurin Java 25.0.4.1+1 at `/opt/java/server`, also pinned for native AMD64/ARM64 and retaining its legal materials. Only Java 17 enters the bounded validator sandbox. See the [runtime decision and exact inputs](../../docs/java-runtime-compatibility.md); published images through alpha.3 remain unchanged.
- The locked validator JARs retain their original license/notice resources; the NuaForma module JARs contain the project LICENSE. Kotlin's upstream copyright/notice texts are also retained separately because the standard-library JAR has no embedded license file. The shared Apache-2.0 text covers the reviewed Apache-licensed components, including JetBrains annotations; the exact annotation artifact's POM identifies Apache-2.0. PDFBox has bundled additional terms, not a replacement license for the combined image.

Validator-root Debian copyright material remains under `/opt/nuaforma/renderer/usr/share/doc`; outer Debian/font notices remain under `/usr/share/doc`. The adjacent NOTICE attributes the adapted Moby profile and the image retains the Apache-2.0 text. Preserve all component-specific rights, including recipients' rights to replace/rebuild LGPL executables. Integrity checks protect qualification, not restrict license-granted modification rights.

The image retains `outer-packages.tsv`, `renderer-packages.tsv`, `validator-sha256.txt` and `verapdf-sha256.txt` under `/usr/share/nuaforma`, recording binary/source versions and staged JAR identities. The separate `/opt/nuaforma/verapdf-validation` distribution contains the reviewed veraPDF runtime, original MPL/GPL notices, profile attribution/CC-BY-4.0 text and retained source/profile inventory. `inspect_licenses.sh`, mounted read-only and executed with `/bin/sh` inside the image, checks retained materials and emits the package inventory plus the Bubblewrap source hash and Java version. This is an inventory check, not a complete legal audit or vulnerability scan.

Before any image/binary publication, complete a fresh [candidate distribution audit](../../docs/container-releases.md). Archive its exact inventory, artifacts, notices and matching corresponding sources, verify recipient source access and any required offers, and package them alongside that release. Debian source versions come from the emitted inventory and pinned snapshot; both Temurin installations need matching sources and build materials. This native image has no LibreOffice component or corresponding Office-source requirement. Separate native release tags and release-tooling migration remain later, explicitly authorized work; a successful local build does not authorize publication.

## Reproducible qualification

The registered-provider and REST tests exercise native rendering and independent validation. `renderNativeDocxProof` invokes the actual provider on explicitly selected synthetic DOCX/font inputs. The [Word proofs](../../tools/pdf-evaluation/own-engine/README.md) and [engine golden files](../../nuaforma-pdf/src/test/resources/visual-goldens/README.md) retain the qualified layout and protected references. Candidate PDFs and new Word exports use fresh output directories; tests never replace golden files.

For the assembled service, follow [build and qualification](SERVICE.md#qualification-and-release-preparation):

```sh
python3 tools/container/qualify_service.py --image nuaforma:local --output build/native-service-qualification
python3 tools/container/collect_inventory.py --image nuaforma:local --destination build/native-runtime-inventory
```

Qualify both AMD64 and ARM64, including genuine standard/PDF-A validation, both operator policies, safe rejection, font mounts, concurrent requests, cancellation/deadline cleanup, shutdown and shipped SBOMs. Keep private fonts and generated binaries out of Git and release uploads until the exact distribution gate is complete. Unsupported source constructs, PDF watermarks and mixed inline text/image paragraphs reject; use the [provider profile](../../nuaforma-docx-pdf/README.md) as the authority.

### Headless device boundary

Only independent validation uses the private Bubblewrap filesystem and device boundary. The launcher provides a private `/dev` tmpfs and `/dev/shm`, and individually binds `/dev/null`, `/dev/zero`, `/dev/full`, `/dev/random` and `/dev/urandom`. There is no terminal-device mount, host device-directory bind or interactive desktop requirement. Namespace, capability, seccomp and resource limits remain mandatory; isolation failure leaves PDF unavailable.

The synthetic probe checks the production device arguments, identities/usability, private shared memory and absent terminal paths. Qualify the actual host kernel/security policy; do not disable SELinux, AppArmor or seccomp to make a deployment start. Native rendering itself stays in the hosting JVM with bounded admission and cooperative cancellation.
