# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# Local qualification/service targets. Binary redistribution remains gated by
# the candidate-specific distribution audit and first-release prerequisites.
FROM debian:trixie-slim@sha256:918311b7b6c4c6f68b232ba516584925f6c78ad82b6fd534b98979df6438e483 AS base
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

FROM base AS sandbox-check
RUN apt-get update && apt-get install -y --no-install-recommends libcap2
COPY --from=base / /opt/nuaforma/renderer/
COPY --from=bubblewrap-build /src/build/bwrap /usr/local/bin/bwrap
COPY --from=bubblewrap-build /src/COPYING /usr/share/nuaforma/licenses/bubblewrap/COPYING
COPY --from=bubblewrap-build /src/COPYING.LIB /usr/share/nuaforma/licenses/bubblewrap/COPYING.LIB
COPY --from=bubblewrap-build /tmp/bubblewrap.tar.xz /usr/share/nuaforma/sources/bubblewrap-0.13.0.tar.xz
COPY --from=bubblewrap-build /src/renderer-seccomp.bpf /usr/share/nuaforma/renderer-seccomp.bpf
USER 1000:1000
ENTRYPOINT ["/usr/local/bin/bwrap"]

FROM base AS libreoffice
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl fontconfig
RUN curl --fail --location --output /tmp/libreoffice.tar.gz https://download.documentfoundation.org/libreoffice/stable/26.8.1/deb/x86_64/LibreOffice_26.8.1_Linux_x86-64_deb.tar.gz \
    && echo '30903df3b9f61360d9660cd707de48cd2831469114492a5008ed58a0ac77d044  /tmp/libreoffice.tar.gz' | sha256sum --check \
    && mkdir /tmp/lo && tar -xzf /tmp/libreoffice.tar.gz -C /tmp/lo \
    && apt-get install -y --no-install-recommends /tmp/lo/*/DEBS/*.deb
RUN apt-get install -y --no-install-recommends libxinerama1 libx11-6 libx11-xcb1 libxext6 libxrender1 libxrandr2 libsm6 libice6 libnss3 libnspr4 libdbus-1-3 libcairo2 libglib2.0-0t64 libcups2t64 \
    && mkdir /work && mkdir -p /validator/java /validator/lib

FROM eclipse-temurin:17-jre-noble@sha256:af3852c35f8339ab7beba6f17b7fc15c4aca4d72979c5f09776c9ff74385058f AS java-runtime

FROM sandbox-check AS qualification
COPY --from=libreoffice / /opt/nuaforma/renderer/
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk/
COPY --from=pdfvalidation / /opt/nuaforma/pdf-validation/
COPY --from=verapdfvalidation / /opt/nuaforma/verapdf-validation/
COPY render-pdf.sh /opt/nuaforma/bin/render-pdf
COPY NOTICE /usr/share/nuaforma/NOTICE
COPY licenses/APACHE-2.0.txt /usr/share/nuaforma/licenses/APACHE-2.0.txt
COPY licenses/kotlin /usr/share/nuaforma/licenses/kotlin/
USER root
RUN chmod 0555 /opt/nuaforma/bin/render-pdf \
    && dpkg-query -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n' > /usr/share/nuaforma/outer-packages.tsv \
    && dpkg-query --admindir=/opt/nuaforma/renderer/var/lib/dpkg -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n' > /usr/share/nuaforma/renderer-packages.tsv \
    && sha256sum /opt/nuaforma/pdf-validation/*.jar > /usr/share/nuaforma/validator-sha256.txt
RUN test -s /opt/nuaforma/verapdf-validation/licenses/MPL-2.0.txt \
    && test -s /opt/nuaforma/verapdf-validation/licenses/CC-BY-4.0.txt \
    && test -s /opt/nuaforma/verapdf-validation/licenses/PROFILES-ATTRIBUTION.md \
    && test -s /opt/nuaforma/verapdf-validation/materials/inventory.json \
    && sha256sum /opt/nuaforma/verapdf-validation/*.jar > /usr/share/nuaforma/verapdf-sha256.txt
USER 1000:1000
ENTRYPOINT ["/opt/nuaforma/bin/render-pdf"]

# Only the explicit Gradle installDist directory is accepted, never the checkout.
FROM qualification AS service
COPY --from=application / /opt/nuaforma/application/
COPY start-server.sh /opt/nuaforma/bin/start-server
USER root
RUN chmod 0555 /opt/nuaforma/bin/start-server \
    && mkdir -p /opt/nuaforma/custom-fonts /opt/nuaforma/extensions \
    && chmod 0555 /opt/nuaforma/custom-fonts /opt/nuaforma/extensions \
    && test -s /opt/nuaforma/application/LICENSE \
    && test -s /opt/nuaforma/application/THIRD_PARTY.md \
    && sha256sum /opt/nuaforma/application/lib/*.jar > /usr/share/nuaforma/application-sha256.txt
USER 1000:1000
EXPOSE 8080
STOPSIGNAL SIGTERM
LABEL org.opencontainers.image.source="https://github.com/NuaForma/NuaForma" \
      org.opencontainers.image.title="NuaForma" \
      org.opencontainers.image.description="DOCX and isolated PDF generation; bundled components retain their own licenses"
ENTRYPOINT ["/opt/nuaforma/bin/start-server"]
