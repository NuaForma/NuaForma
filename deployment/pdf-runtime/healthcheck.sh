#!/bin/sh
set -eu
exec /opt/java/server/bin/java -Xms8m -Xmx32m -cp '/opt/nuaforma/application/lib/*' dev.nuaforma.server.HealthCheck "${NUAFORMA_PORT:-8080}"
