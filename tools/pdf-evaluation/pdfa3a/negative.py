"""Deliberately break valid synthetic 3a outputs and verify explicit rejection, not exit-code-only acceptance."""
import argparse
import concurrent.futures
import json
import pathlib
import shutil
from run import execute
from matrix import report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--matrix", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    args.output.mkdir(parents=True, exist_ok=False)
    source = args.matrix / "letter/pdfa3b/metadata-only/output/input.pdf"
    records = []
    def work(name, pdf):
        directory = (args.output / name).resolve()
        directory.mkdir()
        for folder in ("output", "fonts", "home", "cache", "profile", "tmp"):
            (directory / folder).mkdir()
        shutil.copyfile(pdf, directory / "output/input.pdf")
        return directory
    for mode in ("negative", "negative-alt", "negative-unicode", "negative-lang", "negative-icc"):
        directory = work(mode, source)
        assert execute(root, directory, mode, args.docker, args.image)["exitCode"] == 0
        shutil.copyfile(directory / "output/negative.pdf", directory / "output/input.pdf")
        status = execute(root, directory, "validate-3a", args.docker, args.image)
        result = report(directory / "result.txt")
        if mode != "negative-alt":
            assert not result["compliant"] and result["failedRules"], (mode, result)
        records.append({"case": mode, "exitCode": status["exitCode"], "result": result,
                        "needsAdditionalSemanticChecks": mode == "negative-alt" and result["compliant"]})
        print(json.dumps(records[-1]), flush=True)
    malformed = work("malformed", source)
    (malformed / "output/input.pdf").write_bytes(b"%PDF-1.7\ntruncated synthetic file")
    status = execute(root, malformed, "validate-3a", args.docker, args.image)
    try:
        report(malformed / "result.txt")
    except (AssertionError, StopIteration):
        records.append({"case": "malformed", "reportRejected": True, "exitCode": status["exitCode"]})
    else:
        raise AssertionError("Malformed input must not produce an accepted complete conformance report")
    parallel_work = [work("concurrent-letter", source), work("concurrent-simple", args.matrix / "simple/pdfa3b/metadata-only/output/input.pdf")]
    def validate(directory):
        assert execute(root, directory, "validate-3a", args.docker, args.image)["exitCode"] == 0
        result = report(directory / "result.txt")
        assert result["compliant"]
        return result
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        list(executor.map(validate, parallel_work))
    records.append({"case": "independent-concurrent-validation", "passed": True})
    (args.output / "results.json").write_text(json.dumps(records, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
