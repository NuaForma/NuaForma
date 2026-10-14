"""Check emitted classic BPF policy; this does not replace native sandbox probes."""
import argparse
import pathlib
import struct

ARCHITECTURES = {"amd64": 0xC000003E, "arm64": 0xC00000B7}
SYSCALLS = {
    "amd64": {"read": 0, "socket": 41, "clone": 56, "clone3": 435, "denied": [272, 308, 165, 166, 155, 161, 442, 430, 431, 432, 433, 428, 429, 101, 310, 311, 321, 298, 304, 250, 248, 249]},
    "arm64": {"read": 63, "socket": 198, "clone": 220, "clone3": 435, "denied": [97, 268, 40, 39, 41, 51, 442, 430, 431, 432, 433, 428, 429, 117, 270, 271, 280, 241, 265, 219, 217, 218]},
}
ALLOW, KILL = 0x7FFF0000, 0x80000000
DENIED, UNIMPLEMENTED = 0x00050001, 0x00050026


def evaluate(program, architecture, number, argument=0):
    data = struct.pack("<iIQQQQQQQ", number, architecture, 0, argument, 0, 0, 0, 0, 0)
    instructions = list(struct.iter_unpack("<HBBI", program))
    accumulator, position = 0, 0
    for _ in range(len(instructions) * 2):
        code, yes, no, value = instructions[position]
        if code == 0x20:  # BPF_LD | BPF_W | BPF_ABS
            accumulator = struct.unpack_from("<I", data, value)[0]
        elif code == 0x15:  # BPF_JMP | BPF_JEQ | BPF_K
            position += yes if accumulator == value else no
        elif code == 0x45:  # BPF_JMP | BPF_JSET | BPF_K
            position += yes if accumulator & value else no
        elif code == 0x06:  # BPF_RET | BPF_K
            return value
        else:
            raise ValueError("Unexpected filter instruction")
        position += 1
    raise ValueError("Filter did not terminate")


def verify(program, architecture):
    if not program or len(program) % 8:
        raise ValueError("Invalid filter byte length")
    audit = ARCHITECTURES[architecture]
    numbers = SYSCALLS[architecture]
    checks = [(audit, numbers["read"], 0, ALLOW), (0, numbers["read"], 0, KILL),
              (ARCHITECTURES["arm64" if architecture == "amd64" else "amd64"], numbers["read"], 0, KILL),
              (audit, 0x40000000, 0, KILL), (audit, numbers["clone3"], 0, UNIMPLEMENTED),
              (audit, numbers["socket"], 1, ALLOW), (audit, numbers["socket"], 2, DENIED),
              (audit, numbers["socket"], 10, DENIED), (audit, numbers["socket"], 16, DENIED),
              (audit, numbers["clone"], 17, ALLOW), (audit, numbers["clone"], 0x3D0F00, ALLOW)]
    checks += [(audit, number, 0, DENIED) for number in numbers["denied"]]
    checks += [(audit, numbers["clone"], flag | 17, DENIED) for flag in (0x10000000, 0x20000, 0x20000000, 0x40000000, 0x8000000, 0x4000000, 0x2000000, 0x80)]
    for audit_arch, number, argument, expected in checks:
        if evaluate(program, audit_arch, number, argument) != expected:
            raise ValueError(f"Sandbox policy mismatch: arch={audit_arch:x}, syscall={number}, arg={argument:x}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("filter", type=pathlib.Path)
    parser.add_argument("--architecture", required=True, choices=tuple(ARCHITECTURES))
    args = parser.parse_args()
    verify(args.filter.read_bytes(), args.architecture)
    print("Compiled " + args.architecture + " renderer filter policy: PASS (native qualification still required)")
