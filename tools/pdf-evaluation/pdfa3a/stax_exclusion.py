"""Test JSON reporting with StAX utility entries physically absent; research, not distribution."""
import argparse
import hashlib
import json
import pathlib
import shutil
import zipfile
from run import execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    original = root / "build/pdfa-feasibility/installation/cli/bin/cli-1.30.3.jar"
    excluded = output / "excluded"
    excluded.mkdir()
    removed = []
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(excluded / original.name, "w") as target:
        for entry in source.infolist():
            if entry.filename.startswith("javanet/staxutils/"):
                removed.append(entry.filename)
            else:
                target.writestr(entry, source.read(entry))
    assert removed and any(name.endswith("IndentingXMLStreamWriter.class") for name in removed)
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(excluded / original.name) as target:
        assert set(target.namelist()) == set(source.namelist()) - set(removed)
        assert all(source.read(name) == target.read(name) for name in target.namelist())
    ledger = {"originalSha256": hashlib.sha256(original.read_bytes()).hexdigest(),
              "excludedSha256": hashlib.sha256((excluded / original.name).read_bytes()).hexdigest(),
              "removedEntries": removed, "results": []}
    (output / "results.json").write_text(json.dumps(ledger, indent=2), encoding="utf-8")
    source_pdf = root / "build/pdfa-feasibility/matrix-final/simple/pdfa3b/output/input.pdf"
    for name, cli, mode in (("original-json", original.parent, "json-3b"),
                            ("excluded-json", excluded, "json-3b"), ("excluded-xml", excluded, "xml-3b")):
        work = output / name
        work.mkdir()
        for folder in ("output", "home", "tmp", "cache"):
            (work / folder).mkdir()
        shutil.copyfile(source_pdf, work / "output/input.pdf")
        status = execute(root, work, mode, args.docker, args.image,
                         launcher=root / "tools/pdf-evaluation/pdfa3a/stax-launch.sh", cli=cli)
        record = {"case": name, "process": status}
        if (work / "result.txt").exists() and mode.startswith("json"):
            try:
                record["report"] = json.loads((work / "result.txt").read_text(encoding="utf-8"))
            except ValueError as failure:
                record["invalidJson"] = str(failure)
        ledger["results"].append(record)
        (output / "results.json").write_text(json.dumps(ledger, indent=2), encoding="utf-8")
        print(name, status["exitCode"], status["stderr"], flush=True)


if __name__ == "__main__":
    main()
