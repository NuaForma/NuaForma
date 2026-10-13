# Docker evaluation deployment

Use the published Linux/amd64 image with the included Compose file and reviewed seccomp profile. This kit does not rebuild the renderer. The optional Dockerfile is a minimal example for your own derived image; preserve component notices/source obligations if you redistribute derived images.

```sh
mkdir -p custom-fonts extensions
docker compose up -d
curl --fail http://127.0.0.1:8080/api/v1/capabilities
```

The service is available on loopback port 8080. Stop with `docker compose down`. There is no built-in authentication or TLS; do not publish the HTTP port to an untrusted network. Add an authenticated TLS gateway and request quotas if remote callers are needed.

## Fonts

Place standalone TTF/OTF files in `custom-fonts`. UID/GID 1000 needs read/traverse access. Compose mounts this directory read-only at `/opt/nuaforma/custom-fonts`; never mount it into the renderer root. Capabilities lists families/faces and logical resource IDs. Restart after changes because fonts are snapshotted at startup. WOFF/TTC, symlinks and conflicting family/face files are rejected. Availability does not waive embedding flags or your responsibility for font usage rights.

`extensions` is an optional read-only mount at `/opt/nuaforma/extensions`. Only install operator-trusted Java 17 provider JARs; installed code has full application-process permissions, not a template sandbox.

Keep non-root/read-only execution, init, dropped capabilities, no-new-privileges, the reviewed profile and limits. Do not use privileged or unconfined execution to work around unavailable PDF. Check the PDF provider's available formats in capabilities. Linux/amd64 on Docker Desktop is qualified; other runtime/kernel policies need independent qualification.

## Source and licenses

The runtime includes LibreOffice, Bubblewrap, veraPDF, Java and OS components under their original licenses. It is not wholly Apache-2.0. Matching source/notices are provided with [the alpha release](https://github.com/NuaForma/NuaForma/releases/tag/v0.1.0-alpha.1). The copied seccomp profile retains its upstream attribution in `NOTICE` and Apache-2.0 text in `LICENSE`.
