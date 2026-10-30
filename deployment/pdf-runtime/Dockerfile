# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# Local qualification/service targets. Binary redistribution remains gated by
# the candidate-specific distribution audit and first-release prerequisites.
# Pinned multi-platform inputs; both architectures require native qualification.
ARG TARGETARCH
FROM debian:trixie-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4 AS base
ARG TARGETARCH
RUN case "$TARGETARCH" in amd64|arm64) ;; *) echo "Unsupported runtime architecture: $TARGETARCH" >&2; exit 64 ;; esac \
    && test "$(dpkg --print-architecture)" = "$TARGETARCH"
COPY debian.sources /etc/apt/sources.list.d/debian.sources

FROM base AS inventory-build
RUN apt-get update && apt-get install -y --no-install-recommends python3

# Both JVMs use the ordinary non-root container boundary and its default host policies.
FROM base AS runtime-system
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends libstdc++6 zlib1g libfreetype6 libfontconfig1 fonts-liberation \
    && test -x /usr/bin/prlimit

# Official signed Temurin 17.0.21+9 archives; architecture-specific hashes are reviewed inputs.
FROM scratch AS java17-amd64
ADD --checksum=sha256:9f41bf88c7900c7097444a1bdeb84146ae692bc08842490ab098063c74bf9a8a https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jre_x64_linux_hotspot_17.0.21_9.tar.gz /jre.tar.gz
FROM scratch AS java17-arm64
ADD --checksum=sha256:1114f09d6174f54270c3117410b5e4022b43ebc0b43b751af08b927c55ed7bb5 https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jre_aarch64_linux_hotspot_17.0.21_9.tar.gz /jre.tar.gz
FROM java17-${TARGETARCH} AS java17-archive
FROM base AS java-runtime
COPY --from=java17-archive /jre.tar.gz /tmp/jre.tar.gz
RUN mkdir -p /opt/java/openjdk \
    && tar -xzf /tmp/jre.tar.gz --strip-components=1 -C /opt/java/openjdk \
    && rm /tmp/jre.tar.gz

# Server Java 25 is separate from the bounded Java 17 validators.
FROM eclipse-temurin:25-jre-noble@sha256:2ef7a81085e0502d7d4ff7e7c0257b6d4f46433f8ea6021180aa3c8a2b9ca3ec AS server-java-runtime

FROM runtime-system AS qualification
ARG NUAFORMA_CONTAINER_VERSION=0.3.0-alpha.1
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
      dev.nuaforma.validation-boundary="container" \
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

FROM inventory-build AS service-inventory
ARG TARGETARCH
COPY generate-sbom.py /generate-sbom.py
COPY --from=service-base /opt/nuaforma/application /inventory/application/
COPY --from=service-base /opt/nuaforma/pdf-validation /inventory/pdf-validation/
COPY --from=service-base /opt/nuaforma/verapdf-validation /inventory/verapdf-validation/
COPY --from=service-base /usr/share/nuaforma/outer-packages.tsv /inventory/
COPY --from=service-base /opt/java/server/release /inventory/java-server-release
COPY --from=service-base /opt/java/openjdk/release /inventory/java-validator-release
COPY --from=service-base /var/lib/dpkg/status /inventory/outer-status
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
