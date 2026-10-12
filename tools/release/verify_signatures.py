"""Independently verify staged artifact signatures with a public-only, temporary GnuPG home."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile

from verify_libraries import MODULES, artifacts, verify


def verify_signature(file, fingerprint, home):
    signature = pathlib.Path(str(file) + ".asc")
    result = subprocess.run(["gpg", "--homedir", str(home), "--batch", "--no-auto-key-retrieve", "--status-fd", "1",
                             "--verify", str(signature), str(file)], capture_output=True, text=True, timeout=30)
    statuses = [line.split()[1:] for line in result.stdout.splitlines() if line.startswith("[GNUPG:] ")]
    assert result.returncode == 0, "Artifact signature verification failed"
    assert not any(item[0] in {"BADSIG", "ERRSIG", "EXPSIG", "EXPKEYSIG", "REVKEYSIG", "KEYEXPIRED", "SIGEXPIRED", "FAILURE"} for item in statuses)
    valid = [item for item in statuses if item[0] == "VALIDSIG"]
    assert len(valid) == 1 and (valid[0][1] == fingerprint or valid[0][-1] == fingerprint), "Unexpected signing identity"
    return {"file": file.name, "sha256": hashlib.sha256(file.read_bytes()).hexdigest(), "fingerprint": fingerprint}


def verify_all(repository, version, fingerprint, public_key):
    assert re.fullmatch(r"[0-9A-F]{40}", fingerprint), "Expected a complete OpenPGP fingerprint"
    assert 0 < public_key.stat().st_size <= 1024 * 1024, "Public verification key exceeds its bound"
    verify(repository, version)
    with tempfile.TemporaryDirectory(prefix="nuaforma-public-verification-") as directory:
        home = pathlib.Path(directory)
        home.chmod(0o700)
        imported = subprocess.run(["gpg", "--homedir", str(home), "--batch", "--import", str(public_key)],
                                  capture_output=True, timeout=30)
        assert imported.returncode == 0, "Public key import failed"
        return [verify_signature(file, fingerprint, home) for module in MODULES
                for file in artifacts(repository, module, version)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=pathlib.Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--fingerprint", required=True)
    parser.add_argument("--public-key", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    records = verify_all(args.repository, args.version, args.fingerprint, args.public_key)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(records, indent=2), encoding="utf-8")
    print("Verified signatures for", len(records), "artifacts with the expected public identity")


if __name__ == "__main__":
    main()
