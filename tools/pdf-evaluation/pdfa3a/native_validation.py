"""Exercise the production child-JVM adapter with unmodified StAX-free upstream library JARs."""
import argparse
import hashlib
import json
import pathlib
import shutil
from run import execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--matrix", type=pathlib.Path)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    runtime = output / "runtime"
    runtime.mkdir()
    for folder in ("verapdf-validation", "pdf-validation"):
        for source in (root / "nuaforma-docx-pdf/build" / folder).glob("*.jar"):
            assert not (runtime / source.name).exists(), source
            shutil.copyfile(source, runtime / source.name)
    records = []
    for fixture in ("simple", "letter", "complex", "barcode", "watermark"):
        for profile in ("1b", "2b", "3b"):
            source = (args.matrix or root / "build/pdfa-feasibility/b-profiles-v1") / fixture / ("pdfa" + profile) / "output/input.pdf"
            work = output / fixture / profile
            work.mkdir(parents=True)
            for folder in ("output", "home", "tmp", "cache"):
                (work / folder).mkdir()
            shutil.copyfile(source, work / "output/input.pdf")
            status = execute(root, work, "native-" + profile, args.docker, args.image,
                             launcher=root / "tools/pdf-evaluation/pdfa3a/stax-launch.sh", cli=runtime)
            assert status["exitCode"] == 0, status
            expected = "NuaForma PDF/A valid pdfa-" + profile + " " + hashlib.sha256(source.read_bytes()).hexdigest()
            assert (work / "result.txt").read_text().strip() == expected
            records.append({"fixture": fixture, "profile": profile, "proof": expected})
            (output / "results.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
            print(fixture, profile, "validated exact bytes", flush=True)
    for name in ("negative-icc", "malformed"):
        work = output / name
        work.mkdir()
        for folder in ("output", "home", "tmp", "cache"):
            (work / folder).mkdir()
        source = root / "build/pdfa-feasibility/negative-v2" / name / "output/input.pdf"
        shutil.copyfile(source, work / "output/input.pdf")
        status = execute(root, work, "native-3b", args.docker, args.image,
                         launcher=root / "tools/pdf-evaluation/pdfa3a/stax-launch.sh", cli=runtime)
        assert status["exitCode"] != 0 and not (work / "result.txt").read_text().strip(), status
        records.append({"negative": name, "exitCode": status["exitCode"]})
        (output / "results.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
        print(name, "rejected", flush=True)


if __name__ == "__main__":
    main()
