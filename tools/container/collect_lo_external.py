"""Retain checksum-pinned LibreOffice external build inputs without executing or extracting them."""
import argparse
import concurrent.futures
import json
import pathlib
import re
import tarfile

from collect_sources import download


def entries(text):
    values = dict(re.findall(r"^([A-Z0-9_]+)\s*:=\s*([^\r\n#]+)", text, re.MULTILINE))
    def expand(value):
        for _ in range(16):
            if "$(" not in value: return value.strip()
            value = re.sub(r"\$\(([A-Z0-9_]+)\)", lambda match: values[match[1]].strip(), value)
        raise AssertionError("Unsupported build-variable expansion")
    result = []
    for key, value in sorted(values.items()):
        if not key.endswith("_TARBALL"): continue
        prefix = key.removesuffix("_TARBALL")
        assert prefix + "_SHA256SUM" in values, "Missing external source SHA-256"
        name, digest = expand(value), expand(values[prefix + "_SHA256SUM"])
        assert re.fullmatch(r"[A-Za-z0-9._+-]+", name) and name not in (".", "..")
        assert re.fullmatch(r"[a-f0-9]{64}", digest)
        result.append((name, digest))
    assert result
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    with tarfile.open(args.source, "r|xz") as archive:
        member = next(member for member in archive if member.name == "libreoffice-26.8.1.1/download.lst")
        assert member.isfile() and member.size <= 1024 * 1024
        text = archive.extractfile(member).read().decode("utf-8")
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "download.lst").write_text(text, encoding="utf-8")
    records, errors = [], []
    def collect(name, digest):
        record = download("https://dev-www.libreoffice.org/src/" + name, args.output / name, "sha256", digest)
        print("Collected external", name, flush=True)
        return record
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        jobs = {executor.submit(collect, name, digest): name for name, digest in entries(text)}
        for future in concurrent.futures.as_completed(jobs):
            try: records.append(future.result())
            except Exception as error:
                errors.append({"file": jobs[future], "errorType": type(error).__name__})
                print("External collection failed", jobs[future], type(error).__name__, flush=True)
    (args.output / "collection.json").write_text(json.dumps({"files": records, "errors": errors, "auditApproved": False}, indent=2), encoding="utf-8")
    assert not errors, "External source collection incomplete; retained report is not approval"


if __name__ == "__main__": main()
