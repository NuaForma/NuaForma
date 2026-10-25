#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
if [ "$#" -ne 0 ]; then
    printf '%s\n' 'Configure NuaForma with operator environment/JVM properties, not command arguments.' >&2
    exit 64
fi
heap=${NUAFORMA_HEAP_MB:-512}
case "$heap" in ''|*[!0-9]*) exit 64 ;; esac
if [ "$heap" -lt 128 ] || [ "$heap" -gt 16384 ]; then exit 64; fi
parallelism=${NUAFORMA_PDF_PARALLELISM-1}
case "$parallelism" in
    [1-8]) ;;
    *) printf '%s\n' 'Invalid NUAFORMA_PDF_PARALLELISM; expected 1 through 8.' >&2; exit 64 ;;
esac
case "${NUAFORMA_APPLICATION:-server}" in
    server) application_main=dev.nuaforma.server.Main ;;
    playground) application_main=dev.nuaforma.playground.Main ;;
    *) printf '%s\n' 'Unsupported NuaForma application.' >&2; exit 64 ;;
esac
for manifest in application-sha256.txt validator-sha256.txt verapdf-sha256.txt java-sha256.txt; do
    test -s "/usr/share/nuaforma/$manifest"
    /usr/bin/sha256sum --check --status "/usr/share/nuaforma/$manifest"
done
exec /opt/java/server/bin/java "-Xmx${heap}m" -Djava.awt.headless=true \
    "-Djava.io.tmpdir=${NUAFORMA_TEMP_DIRECTORY:-/tmp}" \
    "-Dnuaforma.server.host=${NUAFORMA_HOST:-0.0.0.0}" \
    "-Dnuaforma.server.port=${NUAFORMA_PORT:-8080}" \
    "-Dnuaforma.pdf.validator.launcher=${NUAFORMA_PDF_VALIDATOR_LAUNCHER:-/opt/nuaforma/bin/validate-pdf}" \
    "-Dnuaforma.pdf.validator.java=/opt/java/openjdk/bin/java" \
    "-Dnuaforma.pdf.validator.directory=/opt/nuaforma/pdf-validation" \
    "-Dnuaforma.pdf.parallelism=$parallelism" \
    "-Dnuaforma.fonts.directory=${NUAFORMA_FONT_DIRECTORY:-/opt/nuaforma/custom-fonts}" \
    "-Dnuaforma.fonts.bundledDirectories=${NUAFORMA_BUNDLED_FONT_DIRECTORIES:-/usr/share/fonts}" \
    "-Dnuaforma.extensions.directory=${NUAFORMA_EXTENSION_DIRECTORY:-/opt/nuaforma/extensions}" \
    -cp '/opt/nuaforma/application/lib/*' "$application_main"
