"""Compare unchanged vendor JSON reports against StAX-excluded packaging for B profiles."""
import argparse
import hashlib
import json
import pathlib
import shutil
from run import execute


def summary(path, flavour):
    raw = json.loads(path.read_text(encoding="utf-8"))["report"]
    batch = raw["batchSummary"]
    assert batch["totalJobs"] == 1
    assert all(batch[key] == 0 for key in ("outOfMemory", "veraExceptions", "failedEncryptedJobs", "failedParsingJobs"))
    assert len(raw["jobs"]) == 1 and len(raw["jobs"][0]["validationResult"]) == 1
    result = raw["jobs"][0]["validationResult"][0]
    assert result["jobEndStatus"] == "normal" and result["profileName"] == "PDF/A-" + flavour + " validation profile"
    details = result["details"]
    assert details["passedChecks"] > 0 and details["passedRules"] > 0
    total = batch["validationSummary"]
    assert total["totalJobCount"] == total["successfulJobCount"] == 1 and total["failedJobCount"] == 0
    assert total["compliantPdfaCount"] == int(result["compliant"])
    assert total["nonCompliantPdfaCount"] == int(not result["compliant"])
    if result["compliant"]:
        assert details["failedRules"] == details["failedChecks"] == 0
    return result


def comparable(value):
    # Full raw reports are retained. Context strings contain per-JVM object identity hashes.
    if isinstance(value, dict):
        return {key: comparable(item) for key, item in value.items() if key != "context"}
    if isinstance(value, list):
        return [comparable(item) for item in value]
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--excluded", type=pathlib.Path, required=True)
    parser.add_argument("--negative-only", action="store_true")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    original = root / "build/pdfa-feasibility/installation/cli/bin"
    launcher = root / "tools/pdf-evaluation/pdfa3a/stax-launch.sh"
    records = []

    def validate(work, flavour):
        before = hashlib.sha256((work / "output/input.pdf").read_bytes()).hexdigest()
        comparisons = []
        for name, cli in (("original", original), ("excluded", args.excluded.resolve())):
            status = execute(root, work, "json-" + flavour, args.docker, args.image, launcher=launcher, cli=cli)
            shutil.copyfile(work / "result.txt", work / (name + "-report.json"))
            shutil.copyfile(work / ("json-" + flavour + "-process.json"), work / (name + "-process.json"))
            assert status["exitCode"] in (0, 1), status
            comparisons.append(summary(work / (name + "-report.json"), flavour))
        assert comparable(comparisons[0]) == comparable(comparisons[1]), "Validation rules/check totals or outcomes changed"
        assert before == hashlib.sha256((work / "output/input.pdf").read_bytes()).hexdigest()
        return comparisons[0]

    def directories(work):
        work.mkdir(parents=True)
        for folder in ("output", "fonts", "home", "cache", "profile", "tmp"):
            (work / folder).mkdir()

    for fixture in (() if args.negative_only else ("simple", "letter", "complex", "barcode", "watermark")):
        for flavour in ("1b", "2b", "3b"):
            work = output / fixture / ("pdfa" + flavour)
            directories(work)
            prior = root / "build/pdfa-feasibility/matrix-final" / fixture / "pdfa3b"
            shutil.copyfile(prior / "input.docx", work / "input.docx")
            shutil.copyfile(prior / "fonts.conf", work / "fonts.conf")
            for font in (prior / "fonts").glob("*"):
                shutil.copyfile(font, work / "fonts" / font.name)
            status = execute(root, work, "pdfa" + flavour, args.docker, args.image)
            assert status["exitCode"] == 0, status
            result = validate(work, flavour)
            records.append({"fixture": fixture, "flavour": flavour, "result": result})
            (output / "results.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
            print(fixture, flavour, result["compliant"], flush=True)
    for name in ("negative", "negative-unicode", "negative-lang", "negative-icc"):
        prior = root / "build/pdfa-feasibility/negative-v2" / name
        assert (prior / "output/input.pdf").exists(), prior
        work = output / "negative" / name
        directories(work)
        shutil.copyfile(prior / "output/input.pdf", work / "output/input.pdf")
        result = validate(work, "3a")
        assert not result["compliant"]
        records.append({"fixture": name, "negative": True, "result": result})
        (output / "results.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    print("Completed unchanged validation-result comparison", flush=True)


if __name__ == "__main__":
    main()
