"""Refuse an existing registry tag and any ambiguous registry/network/authentication failure."""
import subprocess
import argparse
import re


def check(code, output):
    assert code != 0, "Existing version tag must not be overwritten"
    assert not any(message in output.lower() for message in ("unauthorized", "denied", "forbidden", "timeout", "connection refused", "connection reset")), \
        "Registry authentication/network failure is not proof of absence"
    assert any(message in output.lower() for message in ("manifest unknown", "no such manifest", "name_unknown")), \
        "Registry absence could not be established; refusing push"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", default="ghcr.io/nuaforma/nuaforma:0.1.0-alpha.1")
    args = parser.parse_args()
    assert re.fullmatch(r"ghcr\.io/nuaforma/nuaforma:[A-Za-z0-9][A-Za-z0-9_.-]*", args.reference)
    result = subprocess.run(["docker", "manifest", "inspect", args.reference],
                            capture_output=True, text=True, timeout=60)
    check(result.returncode, result.stdout + result.stderr)
    print("Version tag is absent; no push performed by this check")
