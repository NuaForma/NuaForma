#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Dependency-exclusion research only; never a production launcher.
set -eu
[ "$#" -eq 2 ] || exit 64
work=$1
mode=$2
entry=cli
case "$work" in /*) ;; *) exit 64 ;; esac
case "$mode" in
  native-1b) flavour=pdfa-1b; entry=native ;;
  native-2b) flavour=pdfa-2b; entry=native ;;
  native-3b) flavour=pdfa-3b; entry=native ;;
  json-1b) flavour=1b; format=json ;;
  json-2b) flavour=2b; format=json ;;
  json-3b) flavour=3b; format=json ;;
  json-3a) flavour=3a; format=json ;;
  xml-3b) flavour=3b; format=xml ;;
  *) exit 64 ;;
esac
if [ "$entry" = native ]; then
    set -- -cp '/tmp/research-verapdf/*:/validator/lib/*' dev.nuaforma.docx.pdf.adapters.verapdf.VeraPdfValidator /work/output/input.pdf "$flavour"
else
    set -- -jar /tmp/research-verapdf/cli-1.30.3.jar --flavour "$flavour" --format "$format" /work/output/input.pdf
fi
exec 3</usr/share/nuaforma/renderer-seccomp.bpf
exec /usr/bin/prlimit --core=0 --as=1610612736 --fsize=8388608 --cpu=120 -- \
  /usr/local/bin/bwrap --unshare-user --unshare-ipc --unshare-pid --unshare-net --unshare-uts \
  --die-with-parent --new-session --cap-drop ALL --clearenv \
  --ro-bind /opt/nuaforma/renderer / --proc /proc --dev /dev --tmpfs /tmp \
  --ro-bind /opt/java/openjdk /validator/java \
  --ro-bind /opt/nuaforma/research-verapdf /tmp/research-verapdf \
  --bind "$work" /work --chdir /work --seccomp 3 \
  --setenv HOME /work/home --setenv LANG C.UTF-8 --setenv LC_ALL C.UTF-8 --setenv TMPDIR /work/tmp -- \
  /validator/java/bin/java -Xmx256m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m \
  -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Xss512k \
  -Djava.awt.headless=true -Duser.home=/work/home --add-exports=java.base/sun.security.pkcs=ALL-UNNAMED \
  "$@" > "$work/result.txt"
