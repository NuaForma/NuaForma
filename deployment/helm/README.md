# Source deployment chart

This chart targets the source server with `/health/live` and `/health/ready`. The website's version-pinned alpha deployment kit remains separate until a matching image is released. Its original security prerequisites still apply: install the reviewed `deployment/pdf-runtime/seccomp.json` on each eligible node, allow the qualified unprivileged namespace/device sandbox and retain PID/resource bounds. Do not use privileged/unconfined execution to make probes or conversion succeed.

Build/load or otherwise supply your approved candidate image, then configure `image.repository` and either `image.tag` or `image.digest`. The default `nuaforma:local` is a local candidate name, not a public image promise. Both architectures are selectable via `nodeSelector`; each must be independently qualified. No ingress/authentication framework is installed. Operators control exposure, including on OpenShift; platform-specific security admission may require an explicitly reviewed setup, not reduced protection.

```sh
helm lint deployment/helm/nuaforma
helm template nuaforma deployment/helm/nuaforma
helm upgrade --install nuaforma deployment/helm/nuaforma \
  --set image.repository=<your-approved-image> --set image.tag=<version>
```

`probes.startup`, `probes.liveness` and `probes.readiness` configure period, timeout and failure threshold. Startup/liveness use `/health/live`; readiness uses `/health/ready`, which may stay healthy with DOCX when PDF is unavailable. Query capabilities for exact formats. The application marks readiness false during drain; stopped HTTP naturally produces connection failures. See [health semantics](../../docs/health.md).

At an authorized matching release, use this chart as the source for the website Helm kit, include the unchanged reviewed profiles and their notices, regenerate download archives and verify the published downloads. Do not pair these new health routes with an older immutable alpha image.
