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
exec /opt/java/server/bin/java "-Xmx${heap}m" -Djava.awt.headless=true \
    "-Djava.io.tmpdir=${NUAFORMA_TEMP_DIRECTORY:-/tmp}" \
    "-Dnuaforma.server.host=${NUAFORMA_HOST:-0.0.0.0}" \
    "-Dnuaforma.server.port=${NUAFORMA_PORT:-8080}" \
    "-Dnuaforma.pdf.launcher=${NUAFORMA_PDF_LAUNCHER:-/opt/nuaforma/bin/render-pdf}" \
    "-Dnuaforma.pdf.parallelism=$parallelism" \
    "-Dnuaforma.fonts.directory=${NUAFORMA_FONT_DIRECTORY:-/opt/nuaforma/custom-fonts}" \
    "-Dnuaforma.fonts.bundledDirectories=${NUAFORMA_BUNDLED_FONT_DIRECTORIES:-/opt/nuaforma/renderer/usr/share/fonts:/opt/nuaforma/renderer/opt/libreoffice26.8/share/fonts/truetype}" \
    "-Dnuaforma.extensions.directory=${NUAFORMA_EXTENSION_DIRECTORY:-/opt/nuaforma/extensions}" \
    -cp '/opt/nuaforma/application/lib/*' "$application_main"
