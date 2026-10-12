"""Archive explicit final 3a reports, unchanged safety checks and semantic reading-order evidence."""
import argparse
import json
import hashlib
import pathlib
from run import execute
from matrix import report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--matrix", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    results = []
    for name in ("simple", "letter", "complex", "barcode", "watermark"):
        for export in ("pdfa3b", "pdfa3b-ua"):
            work = (args.matrix / name / export / "metadata-only").resolve()
            assert execute(root, work, "validate-3a", args.docker, args.image)["exitCode"] == 0
            validated = report(work / "validate-3a-result.txt")
            assert validated["compliant"]
            safety = execute(root, work, "validate-safety", args.docker, args.image)
            assert safety["exitCode"] == 0, safety
            assert execute(root, work, "semantic", args.docker, args.image)["exitCode"] == 0
            text = (work / "semantic-result.txt").read_text(encoding="utf-8")
            results.append({"fixture": name, "export": export, "machine3aCompliant": True, "staticSafety": True,
                            "validatedPdfSha256": hashlib.sha256((work / "output/input.pdf").read_bytes()).hexdigest(),
                            "semanticWatermarkStatusPresent": "CONCEPT" in text if name == "watermark" else None,
                            "semanticBarcodePresent": "NF001" in text if name == "barcode" else None})
            (args.matrix / "final-checks.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
            print(json.dumps(results[-1]), flush=True)


if __name__ == "__main__":
    main()
