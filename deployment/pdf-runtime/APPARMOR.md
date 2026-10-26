# Runtime default container policies

The native source container uses the ordinary container runtime boundary. No NuaForma-specific AppArmor profile or seccomp JSON file is installed on the host. Docker keeps its default seccomp filter and, where available, `docker-default` AppArmor confinement. Kubernetes explicitly uses `seccompProfile.type: RuntimeDefault`.

Retain non-root execution, read-only root filesystems, no-new-privileges, dropped capabilities, ordinary protected `/proc`/`sys` paths, process/resource limits, ephemeral temp storage and child reaping. Do not grant `SYS_ADMIN`, mount host namespaces/devices, use privileged/unconfined mode or disable host protections to make qualification pass.

Validation remains in separate bounded Java processes. Those processes share the container's filesystem/network/device boundary; Bubblewrap's per-request namespaces and device allowlist are removed by the maintainer's approved architecture decision. No extra unprivileged namespace permission is needed by NuaForma. Rootless container engines retain their own normal platform prerequisites.

The native candidate checks inspect the actual container settings and process seccomp/no-new-privileges/capability state, then run real generation/validation under the defaults on AMD64 and ARM64. There is no separate custom-profile installation or kernel mount-denial audit. Other container engines and cluster admission policies require their own qualification; a template render alone is not runtime evidence.
