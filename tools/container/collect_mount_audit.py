"""Temporarily expose quiet denial logs on an owned CI runner, never change enforcement."""
import argparse
import datetime
import os
import pathlib
import subprocess
import sys


def verify(log, failure):
    matches = [line for line in log.splitlines() if all(token in line for token in
               ('apparmor="DENIED"', 'operation="mount"', 'profile="docker-default"', 'comm="bwrap"'))]
    if not matches or "Failed to make / slave: Permission denied" not in failure:
        raise ValueError("Matching kernel mount denial and synthetic probe failure required")
    return "\n".join(matches) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--probe-image", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError("Only run on the explicitly owned ephemeral CI runner as administrator")
    parameters = pathlib.Path("/sys/module/apparmor/parameters")
    if (parameters / "enabled").read_text().strip() != "Y" or (parameters / "mode").read_text().strip() != "enforce":
        raise ValueError("Active enforcing AppArmor required")
    audit = parameters / "audit"
    original = audit.read_text().strip()
    if original not in ("normal", "quiet_denied", "quiet", "noquiet", "all"):
        raise ValueError("Unexpected logging mode")
    args.output.mkdir(parents=True, exist_ok=False)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    try:
        audit.write_text("noquiet")
        result = subprocess.run([sys.executable, "tools/container/qualify_sandbox.py", "--image", args.image,
                                 "--probe-image", args.probe_image, "--output", str(args.output / "probe")],
                                capture_output=True, text=True, timeout=90)
        failure = result.stdout + result.stderr
        print(failure, flush=True)
        if result.returncode == 0:
            raise ValueError("Expected inherited mount policy to deny setup")
        log = subprocess.check_output(["journalctl", "--dmesg", "--since", started, "--no-pager"], text=True, timeout=30)
        evidence = verify(log, failure)
        (args.output / "kernel.txt").write_text(evidence, encoding="utf-8")
        print(evidence, flush=True)
    finally:
        audit.write_text(original)
        if audit.read_text().strip() != original or (parameters / "mode").read_text().strip() != "enforce":
            raise RuntimeError("Logging restoration or unchanged enforcement could not be verified")


if __name__ == "__main__":
    main()
