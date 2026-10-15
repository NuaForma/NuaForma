# ARM64 Docker and Podman deployment

This kit selects the native Linux/arm64 image for `0.1.0-alpha.3`, for Apple Silicon and other ARM64 hosts. The AMD64 kit uses the same versioned image index. Keep the included Compose file and reviewed seccomp profile together. This kit does not rebuild the renderer. The optional Dockerfile is a minimal example for your own derived image; preserve component notices/source obligations if you redistribute derived images.

```sh
mkdir -p custom-fonts extensions
docker compose up -d
curl --fail http://127.0.0.1:8080/api/v1/capabilities
```

The service is available on loopback port 8080. Stop with `docker compose down`. There is no built-in authentication or TLS; do not publish the HTTP port to an untrusted network. Add an authenticated TLS gateway and request quotas if remote callers are needed.

## Fonts

Place standalone TTF/OTF files in `custom-fonts`. UID/GID 1000 needs read/traverse access. Compose mounts this directory read-only at `/opt/nuaforma/custom-fonts`; never mount it into the renderer root. Capabilities lists families/faces and logical resource IDs. Restart after changes because fonts are snapshotted at startup. WOFF/TTC, symlinks and conflicting family/face files are rejected. Availability does not waive embedding flags or your responsibility for font usage rights.

`extensions` is an optional read-only mount at `/opt/nuaforma/extensions`. Only install operator-trusted Java 17 provider JARs; installed code has full application-process permissions, not a template sandbox.

Keep non-root/read-only execution, init, dropped capabilities, no-new-privileges, the reviewed profile and limits. Do not use privileged or unconfined execution to work around unavailable PDF. The Linux container host must support unprivileged user namespaces. Check the PDF provider's available formats in capabilities.

## Podman on macOS

Use the standalone `podman-compose` provider rather than the Docker Compose compatibility provider, which can pass the seccomp JSON contents as a filename. The seccomp path must name a file readable inside the Podman Linux VM. Use absolute VM-visible paths for it and the font/extension directories. Verify them through `podman machine ssh` before starting. Merely changing file permissions does not fix a provider/path mismatch.

```sh
export PODMAN_COMPOSE_PROVIDER="$(command -v podman-compose)"
export NUAFORMA_SECCOMP_PROFILE=/absolute/vm-visible/path/seccomp.json
export NUAFORMA_CUSTOM_FONTS_DIR=/absolute/vm-visible/path/custom-fonts
export NUAFORMA_EXTENSIONS_DIR=/absolute/vm-visible/path/extensions
podman compose --file compose.yaml up -d
```

If the provider command is empty, install/configure `podman-compose` first. On Apple Silicon use the ARM64 kit, not an emulated AMD64 image. Stop with `podman compose --file compose.yaml down`. Do not apply the AppArmor override to a SELinux-based VM.

## AppArmor-enabled Linux hosts

Load the included enforcing profile on the actual Linux container host with administrator rights, then select the override. This leaves seccomp and all other isolation settings in place. `--add` refuses to overwrite an existing profile; updates require operator review.

```sh
sudo apparmor_parser --add --skip-cache nuaforma.apparmor
sudo grep -F 'nuaforma-runtime (enforce)' /sys/kernel/security/apparmor/profiles
docker compose --file compose.yaml --file compose.apparmor.yaml up -d
```

AppArmor configuration belongs on the Linux host, not merely on the macOS client. Do not use this override where AppArmor is unavailable, and do not disable a different host security policy.

## Source and licenses

The runtime includes LibreOffice, Bubblewrap, veraPDF, Java and OS components under their original licenses. It is not wholly Apache-2.0. Matching source/notices are provided with [the alpha release](https://github.com/NuaForma/NuaForma/releases/tag/v0.1.0-alpha.3). The copied seccomp profile retains its upstream attribution in `NOTICE` and Apache-2.0 text in `LICENSE`.
