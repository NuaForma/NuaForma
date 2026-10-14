/* SPDX-License-Identifier: Apache-2.0 */
/* Synthetic negative probes only; not installed in the production renderer. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <linux/magic.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <unistd.h>

static void check(int result, const char *name) {
    if (!result) { fprintf(stderr, "Sandbox assertion failed: %s\n", name); exit(1); }
    printf("PASS %s\n", name);
}

int main(int argc, char **argv) {
    int hint = argc == 2 && strcmp(argv[1], "--exec-hint") == 0;
    if (argc != 1 && !hint) return 64;
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
    check(access("/proc/self/status", F_OK) == -1 && errno == ENOENT &&
          access("/proc/1/status", F_OK) == -1 && errno == ENOENT, "procfs-not-exposed");
    if (hint) {
        struct statfs compatibility;
        check(statfs("/proc", &compatibility) == 0 && compatibility.f_type == TMPFS_MAGIC,
              "compatibility-view-is-tmpfs-not-procfs");
        char executable[128] = {0};
        ssize_t length = readlink("/proc/self/exe", executable, sizeof executable - 1);
        check(length == (ssize_t) strlen("/work/sandbox-probe") &&
              strcmp(executable, "/work/sandbox-probe") == 0, "immutable-executable-hint");
        int hint_fd = open("/proc/self/status", O_CREAT | O_WRONLY, 0600);
        check(hint_fd == -1 && errno == EROFS, "compatibility-view-read-only");
        hint_fd = open("/proc/version", O_RDONLY);
        char marker;
        check(hint_fd >= 0 && read(hint_fd, &marker, 1) == 0, "version-marker-has-no-kernel-data");
        close(hint_fd);
    }
    struct __user_cap_header_struct header = { _LINUX_CAPABILITY_VERSION_3, 0 };
    struct __user_cap_data_struct capabilities[2] = {{0}, {0}};
    check(syscall(SYS_capget, &header, capabilities) == 0 &&
          capabilities[0].effective == 0 && capabilities[1].effective == 0 &&
          capabilities[0].permitted == 0 && capabilities[1].permitted == 0 &&
          capabilities[0].inheritable == 0 && capabilities[1].inheritable == 0 &&
          prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1 &&
          prctl(PR_GET_SECCOMP, 0, 0, 0, 0) == 2,
          "capabilities-cleared-and-seccomp-active");
    check(getpid() == 2 && getppid() == 1, "private-pid-namespace-retained");
    check(getrlimit(RLIMIT_NOFILE, &limit) == 0 && limit.rlim_cur <= 1048576, "descriptor-probe-budget");
    int proc_descriptor = 0;
    for (rlim_t descriptor = 0; descriptor < limit.rlim_cur; descriptor++) {
        struct statfs filesystem;
        if (fstatfs((int) descriptor, &filesystem) == 0 && filesystem.f_type == PROC_SUPER_MAGIC)
            proc_descriptor = 1;
    }
    check(!proc_descriptor, "no-inherited-procfs-descriptor");
    fflush(stdout);
    pid_t child = fork();
    if (child == 0) _exit(getppid() == 2 && getpid() > 2 ? 0 : 1);
    check(child >= 0, "child-process-creation-available");
    int child_status = 0;
    check(waitpid(child, &child_status, 0) == child && WIFEXITED(child_status) &&
          WEXITSTATUS(child_status) == 0, "child-process-stays-in-private-pid-namespace");
    check(getuid() == 1000, "non-root-uid");
    return 0;
}
