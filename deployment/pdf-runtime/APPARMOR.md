# Enforcing AppArmor profile candidate

This policy is a candidate under [issue #63](https://github.com/NuaForma/NuaForma/issues/63), not yet a qualified deployment profile. Native GitHub amd64/arm64 kernel audits confirm that `docker-default` denies Bubblewrap's initial `rw, silent, rslave` mount on `/`. The rule is separate from seccomp and prevents sandbox setup even when namespace creation succeeds. Docker Desktop qualification without AppArmor does not qualify an AppArmor-enabled Linux host.

The candidate passes the initial mount/root setup but the native Ubuntu runner subsequently refuses a fresh private procfs mount (`Can't mount proc on /proc: Operation not permitted`) without a matching AppArmor denial. Linux's `mount_too_revealing`/`mnt_already_visible` checks reject unprivileged mounts that would expose existing locked submounts. OCI masked proc paths are a suspected further blocker, not yet a fully demonstrated cause. Do not broaden AppArmor rules, remove masks, reuse the application's procfs or disable PID isolation to conceal this failure. Runtime qualification remains failed until the additional boundary is resolved and independently tested.

Do not disable AppArmor, select `unconfined`, grant host `SYS_ADMIN`, use privileged execution or weaken the existing seccomp filters. `nuaforma.apparmor` is a separate enforcing policy: unlisted mounts remain denied. It permits specific Bubblewrap staging operations, root pivots, private proc/dev/tmp filesystems and binds of the immutable renderer/JRE/validator and private request directories. It denies sensitive kernel-file writes and raw IP/packet, VSOCK and crypto-API networking. The application retains normal network access; inner renderer networking remains independently denied by namespace and BPF isolation.

AppArmor mount permissions do not grant capabilities. The container still runs as UID/GID `1000:1000`, drops all host capabilities, uses `no-new-privileges` and retains the read-only root and resource limits. Bubblewrap temporarily obtains setup privileges only in its child user namespace, then drops them before executing the renderer under the inner filter. The profile does not sandbox trusted application extensions independently.

## Operator setup on an AppArmor-enabled Linux host

The operator must review and load the policy on the actual Linux container host, not inside the container and not merely on the macOS client. Loading a host profile requires administrative rights; the service itself receives none.

```sh
sudo apparmor_parser --add --skip-cache deployment/pdf-runtime/nuaforma.apparmor
sudo grep -F 'nuaforma-runtime (enforce)' /sys/kernel/security/apparmor/profiles
docker compose --file deployment/pdf-runtime/compose.yaml \
  --file deployment/pdf-runtime/compose.apparmor.yaml up -d
```

`--add` refuses an existing profile instead of silently replacing it. Updating an installed profile is a separate operator-reviewed action. The Compose override selects the named profile without changing `docker-default` or the daemon configuration. Confirm the final Compose configuration preserves seccomp and no-new-privileges. A missing profile is an error, not permission to fall back to unconfined execution.

Do not apply the override blindly to hosts without AppArmor, such as a SELinux-based Podman VM. Those hosts need their own native qualification; this profile neither configures nor disables SELinux. Apple Silicon/Podman support remains unqualified until tested on the actual setup.

## Evidence and release gates

`apparmor-audit.yml` confirms the inherited denial on native amd64/arm64 runners. Its administrator-only diagnostic temporarily sets denial logging to `noquiet`, restores the previous logging setting in `finally` and verifies that enforcement stays enabled. It changes no access rule and publishes only matching synthetic Bubblewrap kernel evidence, not the full journal. A passing denial diagnostic is not a passing runtime qualification.

The separate candidate profile must pass real negative probes and the final service, font/mount, barcode, PDF/A, cleanup and cancellation checks on each architecture before release approval. Parsing the policy alone is not proof of enforcement or runtime compatibility. Keep source/license and per-platform image audit gates in place.

References: [Docker AppArmor profiles](https://docs.docker.com/engine/security/apparmor/), [Bubblewrap 0.13.0](https://github.com/containers/bubblewrap/tree/v0.13.0), [Linux AppArmor audit modes](https://github.com/torvalds/linux/blob/v6.8/security/apparmor/audit.c).
