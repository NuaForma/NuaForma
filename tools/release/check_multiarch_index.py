"""Require exactly two Linux platform descriptors in the published OCI index."""
import argparse
import json
import pathlib
import re


def check(index, descriptors=None, config_hashes=None):
    if index.get("schemaVersion") != 2:
        raise ValueError("Unexpected index schema")
    manifests = index.get("manifests", [])
    if len(manifests) != 2:
        raise ValueError("Exactly two approved native image descriptors required")
    platforms = {(item.get("platform", {}).get("os"), item.get("platform", {}).get("architecture")) for item in manifests}
    if platforms != {("linux", "amd64"), ("linux", "arm64")}:
        raise ValueError("Published index must contain amd64 and arm64")
    if any(not re.fullmatch(r"sha256:[a-f0-9]{64}", item.get("digest", "")) for item in manifests):
        raise ValueError("Invalid platform digest")
    if descriptors is not None:
        for architecture in ("amd64", "arm64"):
            details = descriptors[architecture]
            descriptor = details.get("Descriptor", {})
            payload = details.get("SchemaV2Manifest") or details.get("OCIManifest") or {}
            if payload.get("config", {}).get("digest") != "sha256:" + config_hashes[architecture]:
                raise ValueError("Published child configuration differs from approved archive")
            entry = next(item for item in manifests if item["platform"]["architecture"] == architecture)
            if entry["digest"] != descriptor.get("digest"):
                raise ValueError("Index does not reference the exact approved platform manifest")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("index", type=pathlib.Path)
    parser.add_argument("--review", type=pathlib.Path, required=True)
    parser.add_argument("--amd64-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--arm64-manifest", type=pathlib.Path, required=True)
    args = parser.parse_args()
    review = json.loads(args.review.read_text())
    descriptors = {"amd64": json.loads(args.amd64_manifest.read_text()), "arm64": json.loads(args.arm64_manifest.read_text())}
    check(json.loads(args.index.read_text()), descriptors,
          {arch: review["platforms"][arch]["containerConfigSha256"] for arch in ("amd64", "arm64")})
    print("Published version index includes both native Linux architectures: PASS")
