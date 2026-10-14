"""Reject emulated runtime qualification; building under emulation is not qualification."""
import argparse
import json
import subprocess

ALIASES = {"amd64": "amd64", "x86_64": "amd64", "arm64": "arm64", "aarch64": "arm64"}


def verify(image, engine, expected):
    if expected not in ("amd64", "arm64"):
        raise ValueError("Unsupported qualification architecture")
    if image.get("Os") != "linux" or image.get("Architecture") != expected:
        raise ValueError("Candidate image does not match the requested Linux architecture")
    if engine.get("OSType") != "linux" or ALIASES.get(engine.get("Architecture")) != expected:
        raise ValueError("Native Linux engine required; emulated execution cannot qualify the PDF sandbox")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--architecture", required=True, choices=("amd64", "arm64"))
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    image = json.loads(subprocess.check_output([args.docker, "image", "inspect", args.image], text=True, timeout=30))[0]
    engine = json.loads(subprocess.check_output([args.docker, "info", "--format", "{{json .}}"], text=True, timeout=30))
    verify(image, engine, args.architecture)
    print("Native Linux/" + args.architecture + " engine and candidate image: PASS")


if __name__ == "__main__":
    main()
