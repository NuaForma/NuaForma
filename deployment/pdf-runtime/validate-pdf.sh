#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Validator isolation only. Native document rendering executes within the hosting JVM.
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
test -f /usr/share/nuaforma/renderer-seccomp.bpf
exec 3</usr/share/nuaforma/renderer-seccomp.bpf
set -- /validator/java/bin/java -XX:+UseSerialGC -Xmx256m -XX:MaxDirectMemorySize=64m \
    -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m \
    -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true -Duser.home=/tmp \
    -cp '/validator/lib/*:/tmp/verapdf/*' dev.nuaforma.pdf.adapters.verapdf.VeraPdfValidator /work/document.pdf "$profile"
if [ "$mode" = structure ]; then set -- "$@" structure; fi
if [ "$mode" = probe ]; then
    set -- /validator/java/bin/java -XX:+UseSerialGC -Xmx256m -XX:MaxDirectMemorySize=64m \
        -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m \
        -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true -Duser.home=/tmp \
        -cp '/validator/lib/*:/tmp/verapdf/*' dev.nuaforma.pdf.adapters.verapdf.VeraPdfValidator --version
fi
exec /usr/bin/prlimit --core=0 --as=1610612736 --fsize=16777216 --cpu=30 -- \
    /usr/local/bin/bwrap --unshare-user --unshare-ipc --unshare-pid --unshare-net --unshare-uts \
    --die-with-parent --new-session --cap-drop ALL --clearenv \
    --ro-bind /opt/nuaforma/renderer / --tmpfs /dev --dir /dev/shm \
    --dev-bind /dev/null /dev/null --dev-bind /dev/zero /dev/zero --dev-bind /dev/full /dev/full \
    --dev-bind /dev/random /dev/random --dev-bind /dev/urandom /dev/urandom --tmpfs /tmp \
    --ro-bind /opt/java/openjdk /validator/java --ro-bind /opt/nuaforma/pdf-validation /validator/lib \
    --ro-bind /opt/nuaforma/verapdf-validation /tmp/verapdf \
    --ro-bind "$work" /work --chdir /work --seccomp 3 \
    --setenv HOME /tmp --setenv LANG C.UTF-8 --setenv LC_ALL C.UTF-8 --setenv TMPDIR /tmp \
    --setenv LD_LIBRARY_PATH /validator/java/lib:/validator/java/lib/server "$@"
