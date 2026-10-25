# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# Local qualification/service targets. Binary redistribution remains gated by
# the candidate-specific distribution audit and first-release prerequisites.
# The pinned index retains the qualified amd64 child and adds its arm64 sibling.
FROM debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f AS base
ARG TARGETARCH
RUN case "$TARGETARCH" in amd64|arm64) ;; *) echo "Unsupported runtime architecture: $TARGETARCH" >&2; exit 64 ;; esac \
    && test "$(dpkg --print-architecture)" = "$TARGETARCH"
COPY debian.sources /etc/apt/sources.list.d/debian.sources

FROM base AS bubblewrap-build
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gcc libc6-dev libcap-dev meson ninja-build pkg-config xz-utils
RUN curl --fail --location --output /tmp/bubblewrap.tar.xz https://github.com/containers/bubblewrap/releases/download/v0.13.0/bubblewrap-0.13.0.tar.xz \
    && echo '4734237473c0e5d695e4e9034a34e43b2dbf5164655bd13fa59ae376b2b7a765  /tmp/bubblewrap.tar.xz' | sha256sum --check \
    && mkdir /src && tar -xJf /tmp/bubblewrap.tar.xz --strip-components=1 -C /src \
    && meson setup /src/build /src --prefix=/usr/local -Dman=disabled -Dselinux=disabled -Dbash_completion_dir=no \
    && meson compile -C /src/build
COPY renderer-seccomp.c /src/renderer-seccomp.c
RUN cc -Wall -Wextra -Werror -O2 /src/renderer-seccomp.c -o /src/renderer-seccomp \
    && /src/renderer-seccomp > /src/renderer-seccomp.bpf

# Qualification-only target; never copied into the production service.
FROM bubblewrap-build AS sandbox-probe-build
COPY --from=qualificationfixtures sandbox_probe.c /src/sandbox_probe.c
RUN cc -Wall -Wextra -Werror -O2 -static /src/sandbox_probe.c -o /src/sandbox-probe

FROM base AS sandbox-check
RUN apt-get update && apt-get install -y --no-install-recommends libcap2 libstdc++6 zlib1g libfreetype6 libfontconfig1 fonts-liberation
COPY --from=base / /opt/nuaforma/renderer/
RUN mkdir -p /opt/nuaforma/renderer/work
COPY --from=bubblewrap-build /src/build/bwrap /usr/local/bin/bwrap
COPY --from=bubblewrap-build /src/COPYING /usr/share/nuaforma/licenses/bubblewrap/COPYING
COPY --from=bubblewrap-build /src/COPYING.LIB /usr/share/nuaforma/licenses/bubblewrap/COPYING.LIB
COPY --from=bubblewrap-build /tmp/bubblewrap.tar.xz /usr/share/nuaforma/sources/bubblewrap-0.13.0.tar.xz
COPY --from=bubblewrap-build /src/renderer-seccomp.bpf /usr/share/nuaforma/renderer-seccomp.bpf
USER 1000:1000
ENTRYPOINT ["/usr/local/bin/bwrap"]

# Minimal immutable filesystem for the isolated Java validator; no document renderer.
FROM base AS validator-system
RUN apt-get update && apt-get install -y --no-install-recommends libstdc++6 zlib1g libfreetype6 libfontconfig1 \
    && mkdir -p /work /validator/java /validator/lib

# Both architectures use the same Java 17.0.20.1+1 release.
FROM eclipse-temurin:17-jre-noble@sha256:900322f4cc1b9da9d730ae4bb56d8a1dabbaa9cfa32809fc8b542b600370a045 AS java-runtime

# Server Java 25 is separate from the deliberately unchanged bounded validators.
FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab AS server-java-runtime

FROM sandbox-check AS qualification
ARG NUAFORMA_CONTAINER_VERSION=0.2.0-alpha.2
COPY --from=validator-system / /opt/nuaforma/renderer/
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk/
COPY --from=pdfvalidation / /opt/nuaforma/pdf-validation/
COPY --from=verapdfvalidation / /opt/nuaforma/verapdf-validation/
COPY validate-pdf.sh /opt/nuaforma/bin/validate-pdf
COPY NOTICE /usr/share/nuaforma/NOTICE
COPY SOURCES.md /usr/share/nuaforma/SOURCES.md
COPY licenses/APACHE-2.0.txt /usr/share/nuaforma/licenses/APACHE-2.0.txt
COPY licenses/kotlin /usr/share/nuaforma/licenses/kotlin/
USER root
RUN chmod 0555 /opt/nuaforma/bin/validate-pdf \
    && printf '%s' "$NUAFORMA_CONTAINER_VERSION" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[A-Za-z0-9][A-Za-z0-9.-]*)?$' \
    && sed -i "s/0\.1\.0-alpha\.1/$NUAFORMA_CONTAINER_VERSION/g" /usr/share/nuaforma/SOURCES.md \
    && dpkg-query -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n' > /usr/share/nuaforma/outer-packages.tsv \
    && dpkg-query --admindir=/opt/nuaforma/renderer/var/lib/dpkg -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n' > /usr/share/nuaforma/renderer-packages.tsv \
    && sha256sum /opt/nuaforma/pdf-validation/*.jar > /usr/share/nuaforma/validator-sha256.txt
RUN test -s /opt/nuaforma/verapdf-validation/licenses/MPL-2.0.txt \
    && test -s /opt/nuaforma/verapdf-validation/licenses/CC-BY-4.0.txt \
    && test -s /opt/nuaforma/verapdf-validation/licenses/PROFILES-ATTRIBUTION.md \
    && test -s /opt/nuaforma/verapdf-validation/materials/inventory.json \
    && sha256sum /opt/nuaforma/verapdf-validation/*.jar > /usr/share/nuaforma/verapdf-sha256.txt
USER 1000:1000
ENTRYPOINT ["/opt/nuaforma/bin/validate-pdf"]

# Only the explicit Gradle installDist directory is accepted, never the checkout.
FROM qualification AS service-base
COPY --from=server-java-runtime /opt/java/openjdk /opt/java/server/
COPY --from=application / /opt/nuaforma/application/
COPY start-server.sh /opt/nuaforma/bin/start-server
COPY healthcheck.sh /opt/nuaforma/bin/healthcheck
USER root
RUN chmod 0555 /opt/nuaforma/bin/start-server /opt/nuaforma/bin/healthcheck \
    && test -s /opt/java/server/legal/java.base/LICENSE \
    && test -s /opt/java/openjdk/legal/java.base/LICENSE \
    && find /opt/java/openjdk /opt/java/server -type f -exec sha256sum {} + | sort > /usr/share/nuaforma/java-sha256.txt
RUN mkdir -p /opt/nuaforma/custom-fonts /opt/nuaforma/extensions \
    && chmod 0555 /opt/nuaforma/custom-fonts /opt/nuaforma/extensions \
    && test -s /opt/nuaforma/application/LICENSE \
    && test -s /opt/nuaforma/application/THIRD_PARTY.md \
    && test -s /opt/nuaforma/application/third-party-materials/inventory.json \
    && sha256sum /opt/nuaforma/application/lib/*.jar > /usr/share/nuaforma/application-sha256.txt
USER 1000:1000
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["/opt/nuaforma/bin/healthcheck"]
LABEL org.opencontainers.image.source="https://github.com/NuaForma/NuaForma" \
      dev.nuaforma.jvm-layout="server25-validator17" \
      org.opencontainers.image.title="NuaForma" \
      org.opencontainers.image.description="DOCX and native PDF generation with isolated validation; bundled components retain their own licenses"
ENTRYPOINT ["/opt/nuaforma/bin/start-server"]

# Explicit development-only distribution. The production target never
# references this context and contains no Playground code or browser assets.
FROM service-base AS playground-base
COPY --from=playgroundapplication /lib/nuaforma-playground-*.jar /opt/nuaforma/application/lib/
COPY --from=playgroundapplication /third-party-materials/ /opt/nuaforma/application/third-party-materials/
ENV NUAFORMA_APPLICATION=playground NUAFORMA_PLAYGROUND=true NUAFORMA_MODE=development
USER root
RUN sha256sum /opt/nuaforma/application/lib/*.jar > /usr/share/nuaforma/application-sha256.txt
USER 1000:1000
LABEL org.opencontainers.image.title="NuaForma Playground" \
      org.opencontainers.image.description="Development Playground; may log document contents and field values; not for production"

FROM bubblewrap-build AS service-inventory
ARG TARGETARCH
COPY generate-sbom.py /generate-sbom.py
COPY --from=service-base /opt/nuaforma/application /inventory/application/
COPY --from=service-base /opt/nuaforma/pdf-validation /inventory/pdf-validation/
COPY --from=service-base /opt/nuaforma/verapdf-validation /inventory/verapdf-validation/
COPY --from=service-base /usr/share/nuaforma/outer-packages.tsv /usr/share/nuaforma/renderer-packages.tsv /inventory/
COPY --from=service-base /usr/local/bin/bwrap /inventory/bwrap
COPY --from=service-base /usr/share/nuaforma/renderer-seccomp.bpf /inventory/renderer-seccomp.bpf
COPY --from=service-base /opt/java/server/release /inventory/java-server-release
COPY --from=service-base /opt/java/openjdk/release /inventory/java-validator-release
COPY --from=service-base /var/lib/dpkg/status /inventory/outer-status
COPY --from=service-base /opt/nuaforma/renderer/var/lib/dpkg/status /inventory/renderer-status
RUN python3 /generate-sbom.py --root /inventory --architecture "$TARGETARCH" --application server --output /sbom.json

FROM service-inventory AS playground-inventory
ARG TARGETARCH
COPY --from=playground-base /opt/nuaforma/application /inventory/application/
RUN python3 /generate-sbom.py --root /inventory --architecture "$TARGETARCH" --application playground --output /sbom.json

FROM service-base AS service
COPY --from=service-inventory /sbom.json /usr/share/nuaforma/sbom.json
ENV NUAFORMA_SBOM_PATH=/usr/share/nuaforma/sbom.json

FROM playground-base AS playground
COPY --from=playground-inventory /sbom.json /usr/share/nuaforma/sbom.json
ENV NUAFORMA_SBOM_PATH=/usr/share/nuaforma/sbom.json
