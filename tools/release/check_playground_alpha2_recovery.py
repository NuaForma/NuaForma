"""Bind the one approved partial-publication recovery to its existing AMD64 manifest."""
import argparse
import json
import pathlib

VERSION = "0.2.0-alpha.2"
SOURCE = "833da8297d72c8fcdf20ae5231c4332cd99118bd"
AMD64_DIGEST = "sha256:7360db70f26dbf4c0810808b35801d4dbf39054715c0603733095deb8fb8e8fb"


def check(review, details):
    if (review.get("version"), review.get("sourceCommit"), review.get("application")) != (VERSION, SOURCE, "playground"):
        raise ValueError("Recovery is restricted to the reviewed Playground alpha.2")
    descriptor = details.get("Descriptor", {})
    payload = details.get("SchemaV2Manifest") or details.get("OCIManifest") or {}
    platform = descriptor.get("platform", {})
    if descriptor.get("digest") != AMD64_DIGEST or (platform.get("os"), platform.get("architecture")) != ("linux", "amd64"):
        raise ValueError("Existing AMD64 manifest differs from the recorded partial publication")
    if payload.get("schemaVersion") != 2 or payload.get("config", {}).get("digest") != "sha256:" + review["platforms"]["amd64"]["containerConfigSha256"]:
        raise ValueError("Existing AMD64 configuration differs from the approved archive")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", required=True, type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    args = parser.parse_args()
    check(json.loads(args.review.read_text()), json.loads(args.manifest.read_text()))
    print("Existing Playground AMD64 manifest matches the immutable recovery record: PASS")
