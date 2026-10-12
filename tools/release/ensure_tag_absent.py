"""Refuse an existing registry tag and any ambiguous registry/network/authentication failure."""
import subprocess


def check(code, output):
    assert code != 0, "Existing alpha tag must not be overwritten"
    assert not any(message in output.lower() for message in ("unauthorized", "denied", "forbidden", "timeout", "connection refused", "connection reset")), \
        "Registry authentication/network failure is not proof of absence"
    assert any(message in output.lower() for message in ("manifest unknown", "no such manifest", "name_unknown")), \
        "Registry absence could not be established; refusing push"


if __name__ == "__main__":
    result = subprocess.run(["docker", "manifest", "inspect", "ghcr.io/nuaforma/nuaforma:0.1.0-alpha.1"],
                            capture_output=True, text=True, timeout=60)
    check(result.returncode, result.stdout + result.stderr)
    print("Alpha tag is absent; no push performed by this check")
