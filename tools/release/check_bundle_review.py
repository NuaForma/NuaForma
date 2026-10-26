"""Ensure the final signed bundle contains exactly the independently reviewed library bytes."""
import argparse
import json
import pathlib

from verify_libraries import MODULES


def check(bundle, review):
    assert bundle["version"] == review["version"]
    assert bundle["fingerprint"] == "74317C6CE9C61B80A2A1C0B40F736E8D598CB08B"
    expected = {module + "-" + bundle["version"] + suffix for module in MODULES
                for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")}
    actual = {record["file"]: record["sha256"] for record in bundle["signatures"]}
    assert len(bundle["signatures"]) == len(expected), "Require exactly one signature per library artifact"
    assert set(actual) == expected and actual == review["libraryHashes"], "Candidate library bytes differ from review"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-record", type=pathlib.Path, required=True)
    parser.add_argument("--review", type=pathlib.Path, required=True)
    args = parser.parse_args()
    check(json.loads(args.bundle_record.read_text()), json.loads(args.review.read_text()))
    print("Exact signed library bytes match the reviewed candidate")


if __name__ == "__main__": main()
