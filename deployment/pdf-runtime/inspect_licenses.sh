#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
test ! -d /opt/nuaforma/renderer/opt/libreoffice26.8
test -s /usr/share/doc/fonts-liberation/copyright
test -s /usr/share/nuaforma/licenses/bubblewrap/COPYING
test -s /usr/share/nuaforma/licenses/bubblewrap/COPYING.LIB
test -d /opt/java/openjdk/legal
test -d /opt/nuaforma/renderer/usr/share/doc
test -s /opt/nuaforma/verapdf-validation/licenses/MPL-2.0.txt
test -s /opt/nuaforma/verapdf-validation/licenses/CC-BY-4.0.txt
test -s /opt/nuaforma/verapdf-validation/licenses/PROFILES-ATTRIBUTION.md
test -s /opt/nuaforma/verapdf-validation/materials/inventory.json
sha256sum /usr/share/nuaforma/sources/bubblewrap-0.13.0.tar.xz
/opt/java/openjdk/bin/java -version
if test -d /opt/java/server; then
    test -s /opt/java/server/legal/java.base/LICENSE
    /opt/java/server/bin/java -version
    test -s /usr/share/nuaforma/java-sha256.txt
    sha256sum --check --status /usr/share/nuaforma/java-sha256.txt
fi
printf '\nOuter Debian package inventory\n'
dpkg-query -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n'
printf '\nRenderer Debian package inventory\n'
dpkg-query --admindir=/opt/nuaforma/renderer/var/lib/dpkg -W -f='${binary:Package}\t${Version}\t${source:Package}\t${source:Version}\n'
