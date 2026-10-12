#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Trusted research modes only; not a production launcher or caller-selected command.
set -eu
[ "$#" -eq 2 ] || exit 64
work=$1
mode=$2
case "$work" in /*) ;; *) exit 64 ;; esac
limit=134217728
cpu=120
case "$mode" in
  version|help|validate-3a|validate-3b)
    limit=8388608
    case "$mode" in
      version) args=--version ;;
      help) args=--help ;;
      validate-3a) args='--flavour 3a --format xml /work/output/input.pdf' ;;
      validate-3b) args='--flavour 3b --format xml /work/output/input.pdf' ;;
    esac
    # Only the fixed strings above are split into arguments.
    set -- /validator/java/bin/java -Xmx256m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true -Duser.home=/work/home --add-exports=java.base/sun.security.pkcs=ALL-UNNAMED -jar /tmp/research-verapdf/cli-1.30.3.jar $args
    ;;
  standard|pdfa1b|pdfa2b|pdfa3b|pdfa3b-ua)
    case "$mode" in
      standard) filter='pdf:writer_pdf_Export:{"SelectPdfVersion":{"type":"long","value":"17"},"ExportBookmarks":{"type":"boolean","value":"true"},"ExportFormFields":{"type":"boolean","value":"false"}}' ;;
      pdfa1b) filter='pdf:writer_pdf_Export:{"SelectPdfVersion":{"type":"long","value":"1"},"ExportBookmarks":{"type":"boolean","value":"true"},"ExportFormFields":{"type":"boolean","value":"false"},"IsAddStream":{"type":"boolean","value":"false"}}' ;;
      pdfa2b) filter='pdf:writer_pdf_Export:{"SelectPdfVersion":{"type":"long","value":"2"},"ExportBookmarks":{"type":"boolean","value":"true"},"ExportFormFields":{"type":"boolean","value":"false"},"IsAddStream":{"type":"boolean","value":"false"}}' ;;
      pdfa3b) filter='pdf:writer_pdf_Export:{"SelectPdfVersion":{"type":"long","value":"3"},"UseTaggedPDF":{"type":"boolean","value":"true"},"ExportBookmarks":{"type":"boolean","value":"true"},"ExportFormFields":{"type":"boolean","value":"false"},"IsAddStream":{"type":"boolean","value":"false"}}' ;;
      pdfa3b-ua) filter='pdf:writer_pdf_Export:{"SelectPdfVersion":{"type":"long","value":"3"},"UseTaggedPDF":{"type":"boolean","value":"true"},"PDFUACompliance":{"type":"boolean","value":"true"},"ExportBookmarks":{"type":"boolean","value":"true"},"ExportFormFields":{"type":"boolean","value":"false"},"IsAddStream":{"type":"boolean","value":"false"}}' ;;
    esac
    set -- /opt/libreoffice26.8/program/soffice -env:UserInstallation=file:///work/profile --headless --nologo --nodefault --nofirststartwizard --norestore --convert-to "$filter" --outdir /work/output /work/input.docx
    ;;
  inspect|relabel|negative|negative-alt|negative-unicode|negative-lang|negative-icc)
    set -- /validator/java/bin/java -Xmx256m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true -cp '/tmp/research-probe:/validator/lib/*' PdfaProbe "$mode" /work/output/input.pdf
    ;;
  sandbox)
    cpu=10
    test -f /app/secret.txt
    test -f /other/secret.txt
    test "$SYNTHETIC_TENANT_SECRET" = synthetic-only
    set -- /work/sandbox-probe
    ;;
  validate-safety)
    set -- /validator/java/bin/java -Xmx256m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true -cp '/validator/lib/*' dev.nuaforma.docx.pdf.adapters.pdfbox.PdfOutputValidator /work/output/input.pdf 268435456
    ;;
  semantic)
    set -- /validator/java/bin/java -Xmx256m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Xss512k -Djava.awt.headless=true -cp '/tmp/research-probe:/validator/lib/*' SemanticProbe /work/output/input.pdf
    ;;
  *) exit 64 ;;
esac
exec 3</usr/share/nuaforma/renderer-seccomp.bpf
exec /usr/bin/prlimit --core=0 --as=1610612736 --fsize="$limit" --cpu="$cpu" -- \
  /usr/local/bin/bwrap --unshare-user --unshare-ipc --unshare-pid --unshare-net --unshare-uts \
  --die-with-parent --new-session --cap-drop ALL --clearenv \
  --ro-bind /opt/nuaforma/renderer / --proc /proc --dev /dev --tmpfs /tmp \
  --ro-bind /opt/java/openjdk /validator/java --ro-bind /opt/nuaforma/pdf-validation /validator/lib \
  --ro-bind /opt/nuaforma/research-verapdf /tmp/research-verapdf \
  --ro-bind /opt/nuaforma/research-probe /tmp/research-probe \
  --bind "$work" /work --chdir /work --seccomp 3 \
  --setenv HOME /work/home --setenv LANG C.UTF-8 --setenv LC_ALL C.UTF-8 \
  --setenv SAL_USE_VCLPLUGIN svp --setenv FONTCONFIG_FILE /work/fonts.conf \
  --setenv XDG_CACHE_HOME /work/cache --setenv TMPDIR /work/tmp -- "$@" > "$work/result.txt"
