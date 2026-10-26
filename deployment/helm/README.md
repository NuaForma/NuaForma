# Source deployment chart

This chart targets the native source server with `/health/live` and `/health/ready`. It explicitly uses `seccompProfile.type: RuntimeDefault`; there is no profile file to install on cluster nodes and no named AppArmor or nested-namespace prerequisite. Retain non-root execution, read-only root, no-new-privileges, dropped capabilities and PID/resource bounds. The website's version-pinned kits remain separate until the matching native image is published.

Build/load or otherwise supply your approved candidate image, then configure `image.repository` and either `image.tag` or `image.digest`. The default `nuaforma:local` is a local candidate name, not a public image promise. Both architectures are selectable via `nodeSelector`; each must be independently qualified. No ingress/authentication framework is installed. Operators control exposure, including on OpenShift; platform-specific security admission may require an explicitly reviewed setup, not reduced protection.

```sh
helm lint deployment/helm/nuaforma
helm template nuaforma deployment/helm/nuaforma
helm upgrade --install nuaforma deployment/helm/nuaforma \
  --set image.repository=<your-approved-image> --set image.tag=<version>
```

`probes.startup`, `probes.liveness` and `probes.readiness` configure period, timeout and failure threshold. Startup/liveness use `/health/live`; readiness uses `/health/ready`, which may stay healthy with DOCX when PDF is unavailable. Query capabilities for exact formats. The application marks readiness false during drain; stopped HTTP naturally produces connection failures. See [health semantics](../../docs/health.md).

Unreleased issue #93 source defaults `pdfaValidation` to the exact string `"true"`, mapping to trusted startup `NUAFORMA_PDFA_VALIDATION`. To delegate conformance checks deliberately, use `--set-string pdfaValidation=false`. Only exact strings `true`/`false` are accepted by chart rendering; all other values fail. **PDF/A conformance is not checked by NuaForma** when disabled; the operator must provide any needed downstream validation. PDFBox, export/profile/font restrictions, readiness probes, independent validator processes and resource limits remain mandatory. Production and Playground share the policy and emit a startup warning when disabled. This source-only setting must not be assumed available in published alpha images. Default-on release qualification is unchanged.

At an authorized matching release, use this chart as the source for the website Helm kit, regenerate the download archives and verify their actual contents and published links. The new kit requires no seccomp JSON or custom AppArmor profile. Do not change a deployment kit that still advertises an older immutable image.
