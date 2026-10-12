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
exec /opt/java/openjdk/bin/java "-Xmx${heap}m" -Djava.awt.headless=true \
    "-Djava.io.tmpdir=${NUAFORMA_TEMP_DIRECTORY:-/tmp}" \
    "-Dnuaforma.server.host=${NUAFORMA_HOST:-0.0.0.0}" \
    "-Dnuaforma.server.port=${NUAFORMA_PORT:-8080}" \
    "-Dnuaforma.pdf.launcher=${NUAFORMA_PDF_LAUNCHER:-/opt/nuaforma/bin/render-pdf}" \
    -cp '/opt/nuaforma/application/lib/*' dev.nuaforma.server.Main
