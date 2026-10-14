"""Extract and inspect the candidate's actual BPF bytes without running its code."""
import argparse
import pathlib
import subprocess
import uuid
from verify_renderer_filter import verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--architecture", required=True, choices=("amd64", "arm64"))
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Use a fresh filter output file")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    name = "nuaforma-filter-check-" + uuid.uuid4().hex

    def command(*values):
        return subprocess.check_output([args.docker, *values], text=True, stderr=subprocess.STDOUT, timeout=60).strip()

    command("create", "--name", name, "--label", "dev.nuaforma.filter-check=" + name, args.image)
    try:
        command("cp", name + ":/usr/share/nuaforma/renderer-seccomp.bpf", str(args.output.resolve()))
        verify(args.output.read_bytes(), args.architecture)
    finally:
        if command("inspect", name, "--format", '{{index .Config.Labels "dev.nuaforma.filter-check"}}') != name:
            raise ValueError("Unexpected filter-check container identity")
        command("rm", name)
    print("Actual candidate " + args.architecture + " BPF policy: PASS; native isolation test still required")


if __name__ == "__main__":
    main()
