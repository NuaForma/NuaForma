#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Qualification only: prove outer sentinels exist before entering the sandbox.
set -eu
test -f /app/secret.txt
test -f /other/secret.txt
test "$SYNTHETIC_TENANT_SECRET" = synthetic-only
exec 3</usr/share/nuaforma/renderer-seccomp.bpf
if [ "${SYNTHETIC_EXEC_HINT:-0}" = 1 ]; then
    set -- --tmpfs /proc --dir /proc/self \
        --symlink /work/sandbox-probe /proc/self/exe --symlink /dev/null /proc/version \
        --remount-ro /proc -- /work/sandbox-probe --exec-hint
else
    set -- -- /work/sandbox-probe
fi
exec /usr/bin/prlimit --core=0 --as=1610612736 --fsize=1048576 --cpu=10 -- \
    /usr/local/bin/bwrap --unshare-user --unshare-ipc --unshare-pid --unshare-net --unshare-uts \
    --die-with-parent --new-session --cap-drop ALL --clearenv \
    --ro-bind /opt/nuaforma/renderer / --tmpfs /dev --dir /dev/shm \
    --dev-bind /dev/null /dev/null --dev-bind /dev/zero /dev/zero --dev-bind /dev/full /dev/full \
    --dev-bind /dev/random /dev/random --dev-bind /dev/urandom /dev/urandom --tmpfs /tmp \
    --bind /tmp/nuaforma-sandbox-probe /work --chdir /work --seccomp 3 "$@"
