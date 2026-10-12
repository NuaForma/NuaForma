#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# All arguments are supplied by the trusted adapter, never a document expression.
set -eu
if [ "$#" -ne 4 ] && [ "$#" -ne 5 ]; then exit 64; fi
work=$1
file_limit=$2
cpu_seconds=$3
mode=$4
decoded_limit=${5:-268435456}
case "$work" in /*) ;; *) exit 64 ;; esac
case "$file_limit:$cpu_seconds:$decoded_limit" in *[!0-9:]*) exit 64 ;; esac
case "$mode" in probe|convert|fonts|catalog|validate) ;; *) exit 64 ;; esac
test -d "$work"
test -f /usr/share/nuaforma/renderer-seccomp.bpf
exec 3</usr/share/nuaforma/renderer-seccomp.bpf
if [ "$mode" = probe ]; then
    set -- /bin/sh -c '/opt/libreoffice26.8/program/soffice --version && /validator/java/bin/java -Xmx256m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Xss512k -cp "/validator/lib/*" dev.nuaforma.docx.pdf.adapters.pdfbox.PdfOutputValidator --version'
elif [ "$mode" = validate ]; then
    set -- /validator/java/bin/java -Xmx256m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true -cp '/validator/lib/*' dev.nuaforma.docx.pdf.adapters.pdfbox.PdfOutputValidator /work/output/input.pdf "$decoded_limit"
elif [ "$mode" = fonts ]; then
    set -- /usr/bin/fc-list --format '%{family}\n'
elif [ "$mode" = catalog ]; then
    set -- /usr/bin/fc-list --format '%{family}\t%{weight}\t%{slant}\n'
else
    set -- /opt/libreoffice26.8/program/soffice -env:UserInstallation=file:///work/profile --headless --nologo --nodefault --nofirststartwizard --norestore \
        --convert-to 'pdf:writer_pdf_Export:{"SelectPdfVersion":{"type":"long","value":"17"},"ExportBookmarks":{"type":"boolean","value":"true"},"ExportFormFields":{"type":"boolean","value":"false"}}' \
        --outdir /work/output /work/input.docx
fi
exec /usr/bin/prlimit --core=0 --as=1610612736 --fsize="$file_limit" --cpu="$cpu_seconds" -- \
    /usr/local/bin/bwrap --unshare-user --unshare-ipc --unshare-pid --unshare-net --unshare-uts \
    --die-with-parent --new-session --cap-drop ALL --clearenv \
    --ro-bind /opt/nuaforma/renderer / --proc /proc --dev /dev --tmpfs /tmp \
    --ro-bind /opt/java/openjdk /validator/java --ro-bind /opt/nuaforma/pdf-validation /validator/lib \
    --bind "$work" /work --chdir /work --seccomp 3 \
    --setenv HOME /work/home --setenv LANG C.UTF-8 --setenv LC_ALL C.UTF-8 \
    --setenv SAL_USE_VCLPLUGIN svp --setenv FONTCONFIG_FILE /work/fonts.conf \
    --setenv XDG_CACHE_HOME /work/cache --setenv TMPDIR /work/tmp -- "$@"
