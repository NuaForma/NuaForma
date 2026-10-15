# Enforcing AppArmor profile

This policy is used by the native AMD64/ARM64 container checks; [issue #63](https://github.com/NuaForma/NuaForma/issues/63) is completed, and the source endpoint/Playground delivery passed it in [PR #81](https://github.com/NuaForma/NuaForma/pull/81). Qualification applies to those measured inputs and hosts, not arbitrary AppArmor deployments. Native GitHub amd64/arm64 kernel audits confirm that `docker-default` denies Bubblewrap's initial `rw, silent, rslave` mount on `/`. The rule is separate from seccomp and prevents sandbox setup even when namespace creation succeeds. Docker Desktop qualification without AppArmor does not qualify an AppArmor-enabled Linux host.

The initial candidate subsequently hit a fresh procfs mount refusal (`Can't mount proc on /proc: Operation not permitted`) without a matching AppArmor denial. Linux's `mount_too_revealing`/`mnt_already_visible` checks reject unprivileged mounts that would expose existing locked submounts. The implemented launcher removes renderer procfs exposure entirely while retaining its private PID namespace. OCI masks, readonly paths, host capabilities and both seccomp policies stay unchanged. The application retains its own existing procfs view for process supervision, never bound into the renderer. Repeat the relevant native runtime/fidelity qualification for every changed final candidate.

LibreOffice conversion receives a read-only synthetic tmpfs at `/proc`, containing only a fixed installation-path symlink at `self/exe` and an empty `version` marker linked to `/dev/null`. These are compatibility hints, not live executable identity or kernel-version data. There is no procfs, process list, status, maps, memory or fd directory. Other roles, including the Java validators, receive no such view. Fixed `LD_LIBRARY_PATH` entries refer only to immutable renderer/JRE directories; no request-supplied library path is accepted. The unmodified native `oosplash` supervises `soffice.bin`, retaining its upstream startup/restart behavior rather than inventing an application retry loop. Check all of these assumptions again when upgrading LibreOffice or Java.

Do not disable AppArmor, select `unconfined`, grant host `SYS_ADMIN`, use privileged execution or weaken the existing seccomp filters. `nuaforma.apparmor` is a separate enforcing policy: unlisted mounts remain denied. It permits specific Bubblewrap staging operations, root pivots, private dev/tmp filesystems and binds of the immutable renderer/JRE/validator and private request directories. It grants no fresh procfs mount. It denies sensitive kernel-file writes and raw IP/packet, VSOCK and crypto-API networking. The application retains normal network access; inner renderer networking remains independently denied by namespace and BPF isolation.

AppArmor mount permissions do not grant capabilities. The container still runs as UID/GID `1000:1000`, drops all host capabilities, uses `no-new-privileges` and retains the read-only root and resource limits. Bubblewrap temporarily obtains setup privileges only in its child user namespace, then drops them before executing the renderer under the inner filter. The profile does not sandbox trusted application extensions independently.

## Operator setup on an AppArmor-enabled Linux host

The bounded validator JVMs explicitly use Java 17's serial collector on both architectures. Native ARM64 diagnostics with the ergonomic default collector produced repeated GC-thread allocation warnings, despite a successful validation exit. Such output exceeds the intentionally strict conformance-response budget and is correctly rejected. Serial collection avoids those extra GC workers without increasing the 256 MiB heap, address-space limit or PID budget. Warnings are not suppressed and the exact profile/digest proof remains mandatory. See [Java 17 collector selection](https://docs.oracle.com/en/java/javase/17/gctuning/available-collectors.html).

The operator must review and load the policy on the actual Linux container host, not inside the container and not merely on the macOS client. Loading a host profile requires administrative rights; the service itself receives none.

```sh
sudo apparmor_parser --add --skip-cache deployment/pdf-runtime/nuaforma.apparmor
sudo grep -F 'nuaforma-runtime (enforce)' /sys/kernel/security/apparmor/profiles
docker compose --file deployment/pdf-runtime/compose.yaml \
  --file deployment/pdf-runtime/compose.apparmor.yaml up -d
```

`--add` refuses an existing profile instead of silently replacing it. Updating an installed profile is a separate operator-reviewed action. The Compose override selects the named profile without changing `docker-default` or the daemon configuration. Confirm the final Compose configuration preserves seccomp and no-new-privileges. A missing profile is an error, not permission to fall back to unconfined execution.

Do not apply the override blindly to hosts without AppArmor, such as a SELinux-based Podman VM. Those hosts need their own native qualification; this profile neither configures nor disables SELinux. The maintainer's alpha.3 Apple Silicon/Podman results are recorded in issue #63; they do not qualify changed Java 25 source images or every Podman/SELinux setup.

## Evidence and release gates

`apparmor-audit.yml` confirms the inherited denial on native amd64/arm64 runners. Its administrator-only diagnostic temporarily sets denial logging to `noquiet`, restores the previous logging setting in `finally` and verifies that enforcement stays enabled. It changes no access rule and publishes only matching synthetic Bubblewrap kernel evidence, not the full journal. A passing denial diagnostic is not a passing runtime qualification.

The separate candidate profile must pass real negative probes and the final service, font/mount, barcode, PDF/A, cleanup and cancellation checks on each architecture before release approval. Parsing the policy alone is not proof of enforcement or runtime compatibility. Keep source/license and per-platform image audit gates in place.

The development plain proc-free probe checks thirty-two properties. The compatibility-view variant checks thirty-six, additionally proving tmpfs rather than procfs, the fixed executable hint, read-only protection and an empty version marker. Seven device checks cover the private tmpfs, absent terminal paths, reviewed character-device identities, working null/zero/entropy devices and private shared memory. Both use real kernel calls for PID identity, child creation, capabilities, no-new-privileges and active seccomp; neither relies on synthetic status text. Both check the absence of inherited procfs descriptors within the explicitly bounded descriptor budget. The container tools additionally reject lost OCI masks/readonly paths, host PID/network namespaces, capabilities or unconfined settings before running any probe.

The development headless launcher no longer requests `devpts` or `/dev/tty`. The published AppArmor profile retains its narrowly scoped setup rules for those paths to remain compatible with existing images and deployment kits; it grants no additional access for the new launcher. Do not remove rules from an existing kit without checking the image version it serves.

References: [Docker AppArmor profiles](https://docs.docker.com/engine/security/apparmor/), [Bubblewrap 0.13.0](https://github.com/containers/bubblewrap/tree/v0.13.0), [Linux AppArmor audit modes](https://github.com/torvalds/linux/blob/v6.8/security/apparmor/audit.c).
