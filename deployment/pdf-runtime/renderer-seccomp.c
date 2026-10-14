/* SPDX-License-Identifier: Apache-2.0 */
/* Emit a native amd64/arm64 BPF filter loaded after namespace setup. */
#define _GNU_SOURCE
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/syscall.h>

#if defined(__x86_64__) && !defined(__ILP32__)
#define RENDERER_AUDIT_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define RENDERER_AUDIT_ARCH AUDIT_ARCH_AARCH64
#else
#error "The renderer sandbox supports only native amd64 and little-endian arm64"
#endif

#define DENY(nr) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)

int main(void) {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, RENDERER_AUDIT_ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, 0x40000000, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        DENY(__NR_unshare), DENY(__NR_setns), DENY(__NR_mount), DENY(__NR_umount2),
        DENY(__NR_pivot_root), DENY(__NR_chroot), DENY(__NR_mount_setattr),
        DENY(__NR_fsopen), DENY(__NR_fsconfig), DENY(__NR_fsmount), DENY(__NR_fspick),
        DENY(__NR_open_tree), DENY(__NR_move_mount), DENY(__NR_ptrace),
        DENY(__NR_process_vm_readv), DENY(__NR_process_vm_writev),
        DENY(__NR_bpf), DENY(__NR_perf_event_open), DENY(__NR_open_by_handle_at),
        DENY(__NR_keyctl), DENY(__NR_add_key), DENY(__NR_request_key),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone3, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_socket, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K,
            CLONE_NEWUSER | CLONE_NEWNS | CLONE_NEWPID | CLONE_NEWNET | CLONE_NEWIPC | CLONE_NEWUTS | CLONE_NEWCGROUP | 0x80, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
    size_t count = sizeof(filter) / sizeof(filter[0]);
    return fwrite(filter, sizeof(filter[0]), count, stdout) == count && fflush(stdout) == 0 ? 0 : 1;
}
