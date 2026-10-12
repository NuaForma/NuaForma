"""Independently validate actual delivered service bytes with the verified official CLI."""
import argparse
import hashlib
import json
import pathlib
import shutil
from b_profiles import summary
from run import execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    args.output.mkdir(parents=True, exist_ok=False)
    records = []
    for name, flavour in (("document", "3b"), ("pdfa-1b", "1b"), ("pdfa-2b", "2b"), ("pdfa-3b", "3b"),
                          ("tenant-a", "3b"), ("tenant-b", "3b"), ("watermark-dynamic", "3b"), ("watermark-opaque-pdfa-1b", "1b")):
        source = args.service / (name + ".pdf")
        work = (args.output / name).resolve()
        work.mkdir()
        for folder in ("output", "home", "tmp", "cache"):
            (work / folder).mkdir()
        shutil.copyfile(source, work / "output/input.pdf")
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        status = execute(root, work, "json-" + flavour, args.docker, args.image,
                         launcher=root / "tools/pdf-evaluation/pdfa3a/stax-launch.sh")
        assert status["exitCode"] == 0, status
        result = summary(work / "result.txt", flavour)
        assert result["compliant"] and hashlib.sha256((work / "output/input.pdf").read_bytes()).hexdigest() == digest
        records.append({"file": name, "profile": flavour, "sha256": digest, "validation": result})
        (args.output / "results.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
        print(name, flavour, "official CLI conformance passed", flush=True)


if __name__ == "__main__":
    main()
