#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
test ! -d /opt/nuaforma/renderer
test ! -e /usr/share/nuaforma/renderer-seccomp.bpf
! command -v bwrap
test -s /usr/share/doc/fonts-liberation/copyright
test -d /opt/java/openjdk/legal
test -s /opt/nuaforma/verapdf-validation/licenses/MPL-2.0.txt
test -s /opt/nuaforma/verapdf-validation/licenses/CC-BY-4.0.txt
test -s /opt/nuaforma/verapdf-validation/licenses/PROFILES-ATTRIBUTION.md
test -s /opt/nuaforma/verapdf-validation/materials/inventory.json
/opt/java/openjdk/bin/java -version
if test -d /opt/java/server; then
    test -s /opt/java/server/legal/java.base/LICENSE
    /opt/java/server/bin/java -version
    test -s /usr/share/nuaforma/java-sha256.txt
    sha256sum --check --status /usr/share/nuaforma/java-sha256.txt
fi
printf '\nOuter Debian package inventory\n'
dpkg-query -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n'
