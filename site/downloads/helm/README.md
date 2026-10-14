# Helm evaluation example

This chart is an example, not a claim that an arbitrary Kubernetes cluster is qualified. It does not install an ingress, expose a public endpoint, mount a Docker socket, install host profiles or relax node security. Keep the service internal and add an authenticated TLS gateway before remote access.

## Node prerequisites

- Eligible Linux/amd64 nodes must allow the existing unprivileged-user-namespace Bubblewrap sandbox. Host AppArmor/SELinux/runtime policies may prevent it; do not disable protections or choose privileged execution as a workaround.
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

The image is pinned to the published `0.1.0-alpha.2` multi-platform index digest; this chart's node selector selects AMD64 nodes. HTTP startup/readiness probes use `/api/v1/version`; these do not prove that PDF conversion is available. Inspect the PDF provider's available write formats and readiness diagnostics. The chart requests UID/GID 1000, no service-account token, dropped capabilities, no privilege escalation, a read-only root, two-CPU/2-GiB limits and a 512-MiB memory-backed private temporary volume. Cluster-enforced quota/accounting can differ from Docker tmpfs limits and requires verification on your platform.

## Add your fonts

Create an existing PVC with a flat directory of readable standalone TTF/OTF files. Supply every required face and keep the PVC contents stable at startup. Use:

```sh
helm upgrade --install nuaforma ./nuaforma --set fonts.existingClaim=nuaforma-fonts
```

The PVC mounts read-only at `/opt/nuaforma/custom-fonts`; UID/GID 1000 must be able to read/traverse it. Empty claim means no operator fonts. Fonts are snapshotted at startup, never installed globally or mounted directly into the renderer. Restart the workload after changing them. Inspect the font catalog via capabilities and preserve technical embedding flags and usage rights. Optional trusted extension JARs use `extensions.existingClaim` at `/opt/nuaforma/extensions`; installing code gives it application-process permissions.

## Source and licenses

Original runtime notices and matching sources remain linked from [the alpha release](https://github.com/NuaForma/NuaForma/releases/tag/v0.1.0-alpha.2). Keep them with redistributed copies. The seccomp profile's attribution is retained in `NOTICE` and `LICENSE`. NuaForma does not modify the upstream renderer/validator binaries in this chart.
