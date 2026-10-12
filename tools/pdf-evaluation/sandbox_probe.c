/* SPDX-License-Identifier: Apache-2.0 */
/* Synthetic negative probes only; not installed in the production renderer. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

static void check(int result, const char *name) {
    if (!result) { fprintf(stderr, "Sandbox assertion failed: %s\n", name); exit(1); }
    printf("PASS %s\n", name);
}

int main(void) {
    int fd = open("/work/visible.txt", O_RDONLY);
    check(fd >= 0, "own-request-readable");
    close(fd);
    check(access("/app/secret.txt", F_OK) == -1 && errno == ENOENT, "application-not-mounted");
    check(access("/opt/nuaforma/application/LICENSE", F_OK) == -1 && errno == ENOENT, "shipped-service-not-mounted");
    check(access("/opt/nuaforma/extensions", F_OK) == -1 && errno == ENOENT, "extension-mount-not-exposed");
    check(access("/opt/nuaforma/custom-fonts", F_OK) == -1 && errno == ENOENT, "font-mount-not-exposed");
    check(access("/other/secret.txt", F_OK) == -1 && errno == ENOENT, "sibling-request-not-mounted");
    check(access("/usr/share/nuaforma/sources/bubblewrap-0.13.0.tar.xz", F_OK) == -1 && errno == ENOENT, "outer-root-not-mounted");
    check(getenv("SYNTHETIC_TENANT_SECRET") == NULL, "caller-environment-cleared");
    fd = socket(AF_INET, SOCK_STREAM, 0);
    check(fd == -1 && errno == EPERM, "ipv4-sockets-denied");
    fd = socket(AF_INET6, SOCK_STREAM, 0);
    check(fd == -1 && errno == EPERM, "ipv6-sockets-denied");
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    check(fd >= 0, "private-unix-ipc-available");
    close(fd);
    check(unshare(CLONE_NEWUSER) == -1 && errno == EPERM, "nested-user-namespace-denied");
    check(mount("none", "/tmp", "tmpfs", 0, NULL) == -1 && errno == EPERM, "mount-reconfiguration-denied");
    check(chroot("/") == -1 && errno == EPERM, "chroot-denied");
    fd = open("/etc/nf-synthetic-write", O_CREAT | O_WRONLY, 0600);
    check(fd == -1 && errno == EROFS, "renderer-root-read-only");
    struct rlimit limit;
    check(getrlimit(RLIMIT_CORE, &limit) == 0 && limit.rlim_cur == 0, "core-dumps-disabled");
    check(getrlimit(RLIMIT_CPU, &limit) == 0 && limit.rlim_cur <= 10, "cpu-limit-applied");
    FILE *status = fopen("/proc/self/status", "r");
    check(status != NULL, "private-proc-readable");
    char line[256];
    int no_capabilities = 0, no_privileges = 0, filtered = 0;
    while (fgets(line, sizeof line, status)) {
        if (strncmp(line, "CapEff:", 7) == 0) no_capabilities = strtoull(line + 7, NULL, 16) == 0;
        if (strncmp(line, "NoNewPrivs:", 11) == 0) no_privileges = strtoul(line + 11, NULL, 10) == 1;
        if (strncmp(line, "Seccomp_filters:", 16) == 0) filtered = strtoul(line + 16, NULL, 10) >= 2;
    }
    fclose(status);
    check(no_capabilities && no_privileges && filtered, "capabilities-cleared-and-inner-seccomp-active");
    check(getuid() == 1000, "non-root-uid");
    return 0;
}
