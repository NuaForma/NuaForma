"""Assemble an independently verified immutable-version Central bundle locally; never upload."""
import argparse
import hashlib
import json
import pathlib
import re
import zipfile

from verify_libraries import MODULES, artifacts
from verify_signatures import verify_all


def release_version(version):
    number = r"(?:0|[1-9][0-9]*)"
    identifier = r"(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
    assert re.fullmatch(number + r"\." + number + r"\." + number + r"(?:-" + identifier + r"(?:\." + identifier + r")*)?", version)
    assert "SNAPSHOT" not in version.upper(), "Snapshots are not immutable Central releases"
    return version


def prepare(repository, version, fingerprint, public_key, output):
    release_version(version)
    repository = repository.resolve()
    assert not output.exists(), "Refusing to overwrite a prepared bundle"
    signatures = verify_all(repository, version, fingerprint, public_key)
    selected = []
    for module in MODULES:
        for artifact in artifacts(repository, module, version):
            for suffix in ("", ".asc", ".md5", ".sha1", ".sha256", ".sha512"):
                file = pathlib.Path(str(artifact) + suffix)
                assert not file.is_symlink() and file.resolve().is_relative_to(repository)
                assert file.is_file() and 0 < file.stat().st_size <= 128 * 1024 * 1024
                selected.append(file)
    assert len(selected) == 120 and sum(file.stat().st_size for file in selected) <= 512 * 1024 * 1024
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as bundle:
        for file in sorted(selected):
            entry = zipfile.ZipInfo(file.relative_to(repository).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, file.read_bytes())
    return {"version": version, "fingerprint": fingerprint, "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
            "entries": len(selected), "signatures": signatures, "uploaded": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=pathlib.Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--fingerprint", required=True)
    parser.add_argument("--public-key", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    record = prepare(args.repository, args.version, args.fingerprint, args.public_key, args.output)
    pathlib.Path(str(args.output) + ".json").write_text(json.dumps(record, indent=2), encoding="utf-8")
    print("Verified local Central bundle:", record["entries"], "entries; nothing uploaded")


if __name__ == "__main__":
    main()
