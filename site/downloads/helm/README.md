# Helm evaluation example

This chart is an example, not a claim that an arbitrary Kubernetes cluster is qualified. It does not install an ingress, expose a public endpoint, mount a Docker socket, install host profiles or relax node security. Keep the service internal and add an authenticated TLS gateway before remote access.

See the [documentation index](https://github.com/NuaForma/NuaForma/blob/main/docs/README.md). This kit targets 0.2.0-alpha.1 with explicit liveness/readiness probes and the same reviewed isolation settings.

## Node prerequisites

- Eligible native Linux/amd64 or Linux/arm64 nodes must allow the existing unprivileged-user-namespace Bubblewrap sandbox. Host AppArmor/SELinux/runtime policies may prevent it; do not disable protections or choose privileged execution as a workaround.
- An operator must install the unchanged included `seccomp.json` on each eligible node at `<kubelet-root-dir>/seccomp/nuaforma/seccomp.json` (typically `/var/lib/kubelet/seccomp/nuaforma/seccomp.json`). The chart uses `Localhost`, not `Unconfined` or a silent `RuntimeDefault` substitution. Configure `seccomp.localhostProfile` only if the installed relative path differs.
- Configure a per-pod PID limit of 128 or an equivalently reviewed bound through your kubelet/runtime platform settings. Kubernetes resource requests/limits do not provide a portable per-pod PID setting in this chart. Resource, ephemeral storage and admission policies must be reviewed for your cluster.
- The chart enables `shareProcessNamespace` so the pod sandbox process acts as PID 1 and can reap orphaned descendants; no extra application worker is installed. Only the single trusted application container belongs in this pod. Review this choice for your platform; do not add unrelated/untrusted sidecars to its process namespace.

These requirements follow the [Kubernetes seccomp](https://kubernetes.io/docs/reference/node/seccomp/) and [process namespace](https://kubernetes.io/docs/tasks/configure-pod-container/share-process-namespace/) documentation. Missing local profiles should prevent container creation rather than start unsandboxed conversion. Check actual PDF readiness after deployment.

## Install and inspect

```sh
helm lint ./nuaforma
helm template nuaforma ./nuaforma
helm upgrade --install nuaforma ./nuaforma
kubectl port-forward service/nuaforma 8080:8080
curl --fail http://127.0.0.1:8080/api/v1/capabilities
```

The image is pinned to the published `0.2.0-alpha.1` multi-platform index digest; this chart's node selector selects AMD64 nodes. Startup/liveness probes use `/health/live`; readiness uses `/health/ready`. Readiness can succeed when DOCX is available but PDF is not; these probes do not prove that a particular PDF request can run. Inspect the PDF provider's available write formats and readiness diagnostics. The chart requests UID/GID 1000, no service-account token, dropped capabilities, no privilege escalation, a read-only root, two-CPU/2-GiB limits and a 512-MiB memory-backed private temporary volume. Cluster-enforced quota/accounting can differ from Docker tmpfs limits and requires verification on your platform.

## Add your fonts

Create an existing PVC with a flat directory of readable standalone TTF/OTF files. Supply every required face and keep the PVC contents stable at startup. Use:

```sh
helm upgrade --install nuaforma ./nuaforma --set fonts.existingClaim=nuaforma-fonts
```

The PVC mounts read-only at `/opt/nuaforma/custom-fonts`; UID/GID 1000 must be able to read/traverse it. Empty claim means no operator fonts. Fonts are snapshotted at startup, never installed globally or mounted directly into the renderer. Restart the workload after changing them. Inspect the font catalog via capabilities and preserve technical embedding flags and usage rights. Optional trusted extension JARs use `extensions.existingClaim` at `/opt/nuaforma/extensions`; installing code gives it application-process permissions.

## Source and licenses

Original runtime notices and matching sources remain linked from [the alpha release](https://github.com/NuaForma/NuaForma/releases/tag/v0.2.0-alpha.1). Keep them with redistributed copies. The seccomp profile's attribution is retained in `NOTICE` and `LICENSE`. NuaForma does not modify the upstream renderer/validator binaries in this chart.

## ARM64 and development Playground

The default selector is Linux/amd64. For native ARM64 nodes, add `--set 'nodeSelector.kubernetes\.io/arch=arm64'` to the Helm command. The pinned image index contains both architectures. Both need the same reviewed node prerequisites.

For the separate development Playground, override `image.repository=ghcr.io/nuaforma/nuaforma-playground` and `image.digest=sha256:79523317866415e0c59c7137d7f3b8d8176fdc388c6c089d5f99fe0b8e3a207e`. The Playground defaults to development mode and may log document contents/field values; use synthetic data and your own access controls. After port forwarding, open `http://localhost:8080/playground/`. The production image never includes the UI.
