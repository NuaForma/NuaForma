"""Test Gradle signing with a disposable synthetic identity, never the maintainer's secret key."""
import argparse
import os
import pathlib
import shutil
import subprocess
import tempfile

from verify_libraries import MODULES, artifacts
from verify_signatures import verify_signature


def command(arguments, **options):
    result = subprocess.run(arguments, capture_output=True, timeout=120, **options)
    if result.returncode != 0:
        raise RuntimeError("Synthetic signing check command failed")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=pathlib.Path, required=True)
    parser.add_argument("--version", default="0.1.0-SNAPSHOT")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="nuaforma-synthetic-signing-") as directory:
        home = pathlib.Path(directory)
        home.chmod(0o700)
        gpg = ["gpg", "--homedir", str(home), "--batch"]
        password = "disposable-synthetic-test-only"
        try:
            command(gpg + ["--pinentry-mode", "loopback", "--passphrase-fd", "0", "--quick-generate-key",
                           "NuaForma Synthetic QA <test@example.invalid>", "rsa2048", "sign", "1d"], input=password.encode())
            listed = command(gpg + ["--with-colons", "--list-keys"]).decode().splitlines()
            fingerprint = next(line.split(":")[9] for line in listed if line.startswith("fpr:"))
            key = command(gpg + ["--pinentry-mode", "loopback", "--passphrase-fd", "0", "--armor", "--export-secret-keys", fingerprint],
                          input=password.encode()).decode()
            public = home / "public.asc"
            command(gpg + ["--armor", "--output", str(public), "--export", fingerprint])
            environment = dict(os.environ, MAVEN_SIGNING_KEY=key, MAVEN_SIGNING_PASSWORD=password)
            built = subprocess.run([str(root / "gradlew"), "--no-daemon", "stageLibraries", "-PsignArtifacts=true",
                                    "--no-build-cache", "--no-configuration-cache", "--console=plain"],
                                   cwd=root, env=environment, capture_output=True, text=True, timeout=600)
            (args.output / "gradle.log").write_text(built.stdout + built.stderr, encoding="utf-8")
            assert built.returncode == 0, "Synthetic artifact signing failed; inspect the ignored build log"
            command(["python3", "tools/release/verify_signatures.py", str(args.repository), "--version", args.version,
                     "--public-key", str(public), "--fingerprint", fingerprint, "--output", str(args.output / "signatures.json")], cwd=root)
            source = artifacts(args.repository, MODULES[0], args.version)[1]
            altered = home / "altered.jar"
            shutil.copyfile(source, altered)
            shutil.copyfile(str(source) + ".asc", str(altered) + ".asc")
            with altered.open("ab") as stream: stream.write(b"synthetic tampering")
            unsigned = home / "unsigned.jar"
            shutil.copyfile(source, unsigned)
            for file, identity in ((altered, fingerprint), (source, "0" * 40), (unsigned, fingerprint)):
                try:
                    verify_signature(file, identity, home)
                except AssertionError:
                    pass
                else:
                    raise AssertionError("Tampering, a missing signature or an unexpected signing identity was accepted")
        finally:
            subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "gpg-agent"], capture_output=True, timeout=30)
    print("Twenty synthetic signatures verified; altered bytes, missing signatures and wrong identities rejected; test private key removed")


if __name__ == "__main__":
    main()
