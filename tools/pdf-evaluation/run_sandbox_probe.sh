#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Qualification only: prove outer sentinels exist before entering the sandbox.
set -eu
test -f /app/secret.txt
test -f /other/secret.txt
test "$SYNTHETIC_TENANT_SECRET" = synthetic-only
exec 3</usr/share/nuaforma/renderer-seccomp.bpf
exec /usr/bin/prlimit --core=0 --as=1610612736 --fsize=1048576 --cpu=10 -- \
    /usr/local/bin/bwrap --unshare-user --unshare-ipc --unshare-pid --unshare-net --unshare-uts \
    --die-with-parent --new-session --cap-drop ALL --clearenv \
    --ro-bind /opt/nuaforma/renderer / --proc /proc --dev /dev --tmpfs /tmp \
    --bind /work /work --chdir /work --seccomp 3 -- /work/sandbox-probe
