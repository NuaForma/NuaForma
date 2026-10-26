#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Bounded validator JVM only. Native rendering executes within the hosting JVM.
set -eu
if [ "$#" -ne 3 ]; then exit 64; fi
work=$1
profile=$2
mode=$3
case "$work" in /*) ;; *) exit 64 ;; esac
case "$profile" in standard|pdfa-1b|pdfa-2b|pdfa-3b) ;; *) exit 64 ;; esac
case "$mode" in conformance|structure|probe) ;; *) exit 64 ;; esac
test -d "$work"
if [ "$mode" != probe ]; then test -f "$work/document.pdf"; fi
set -- /opt/java/openjdk/bin/java -XX:+UseSerialGC -Xmx256m -XX:MaxDirectMemorySize=64m \
    -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m \
    -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true "-Duser.home=$work" "-Djava.io.tmpdir=$work" \
    -cp '/opt/nuaforma/pdf-validation/*:/opt/nuaforma/verapdf-validation/*' dev.nuaforma.pdf.adapters.verapdf.VeraPdfValidator "$work/document.pdf" "$profile"
if [ "$mode" = structure ]; then set -- "$@" structure; fi
if [ "$mode" = probe ]; then
    set -- /opt/java/openjdk/bin/java -XX:+UseSerialGC -Xmx256m -XX:MaxDirectMemorySize=64m \
        -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m \
        -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true "-Duser.home=$work" "-Djava.io.tmpdir=$work" \
        -cp '/opt/nuaforma/pdf-validation/*:/opt/nuaforma/verapdf-validation/*' dev.nuaforma.pdf.adapters.verapdf.VeraPdfValidator --version
fi
# The host clears the inherited environment and reaps this process on deadlines/cancellation.
# The ordinary container boundary applies to server and validator alike; no nested namespaces.
exec /usr/bin/prlimit --core=0 --as=1610612736 --fsize=16777216 --cpu=30 -- "$@"
