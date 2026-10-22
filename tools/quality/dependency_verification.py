"""Review Gradle checksums against fresh official repository bytes; check receipts offline."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
METADATA = Path("gradle/verification-metadata.xml")
RECEIPTS = Path("gradle/verification-review.json")
REPOSITORIES = ("https://repo.maven.apache.org/maven2/", "https://plugins.gradle.org/m2/")
NS = {"v": "https://schema.gradle.org/dependency-verification"}


def inventory(path):
    tree = ET.parse(path)
    if tree.getroot().tag != f"{{{NS['v']}}}verification-metadata":
        raise ValueError("Invalid verification metadata namespace")
    config = tree.find("v:configuration", NS)
    if config is None or config.findtext("v:verify-metadata", namespaces=NS) != "true":
        raise ValueError("Dependency metadata verification must be enabled")
    if any(child.tag.rsplit("}", 1)[-1] not in ("verify-metadata", "verify-signatures") for child in config):
        raise ValueError("Verification exemptions and trust rules are not allowed")
    result = {}
    for component in tree.findall("v:components/v:component", NS):
        coordinates = tuple(component.attrib[key] for key in ("group", "name", "version"))
        if not all(re.fullmatch(r"[A-Za-z0-9_.+\-]+", part) for part in coordinates):
            raise ValueError("Invalid dependency coordinates")
        for artifact in component.findall("v:artifact", NS):
            name = artifact.attrib["name"]
            if not re.fullmatch(r"[A-Za-z0-9_.+\-]+", name):
                raise ValueError("Invalid artifact name")
            hashes = artifact.findall("v:sha256", NS)
            if len(hashes) != 1 or len(artifact) != 1 or len(hashes[0]):
                raise ValueError("Each artifact must have exactly one SHA-256, without exemptions")
            value = hashes[0].attrib["value"]
            if not re.fullmatch(r"[0-9a-f]{64}", value):
                raise ValueError("Invalid SHA-256")
            key = (*coordinates, name)
            if key in result:
                raise ValueError("Duplicate artifact")
            result[key] = value
    if not result:
        raise ValueError("Empty dependency verification inventory")
    return result


def relative_url(key):
    group, name, version, artifact = key
    return f"{group.replace('.', '/')}/{name}/{version}/{artifact}"


def review_artifact(item):
    key, expected = item
    repositories = REPOSITORIES[::-1] if key[1].endswith(".gradle.plugin") else REPOSITORIES
    for repository in repositories:
        url = repository + relative_url(key)
        try:
            with urllib.request.urlopen(url, timeout=90) as response:
                location = urlsplit(response.url)
                if location.scheme != "https" or location.hostname not in (
                        "repo.maven.apache.org", "plugins.gradle.org", "plugins-artifacts.gradle.org"):
                    raise ValueError(f"Unapproved repository redirect for {key}")
                digest = hashlib.sha256()
                size = 0
                while chunk := response.read(1024 * 1024):
                    digest.update(chunk)
                    size += len(chunk)
            if digest.hexdigest() != expected:
                raise ValueError(f"Published artifact differs from Gradle checksum: {key}")
            return {"coordinates": list(key), "sha256": expected, "repositoryUrl": url, "bytes": size}
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    raise ValueError(f"No official published artifact found: {key}")


def validate(root=ROOT):
    expected = inventory(root / METADATA)
    receipts = json.loads((root / RECEIPTS).read_text())
    if receipts.get("schemaVersion") != 1:
        raise ValueError("Unsupported review receipt schema")
    reviewed = {}
    for record in receipts["artifacts"]:
        key = tuple(record["coordinates"])
        if key in reviewed or record["repositoryUrl"] not in [base + relative_url(key) for base in REPOSITORIES]:
            raise ValueError("Invalid or duplicate repository review receipt")
        if record["bytes"] <= 0:
            raise ValueError("Empty published artifact")
        reviewed[key] = record["sha256"]
    if reviewed != expected:
        raise ValueError("Dependency checksums need an exact independent repository review")
    properties = (root / "gradle.properties").read_text().splitlines()
    if "org.gradle.dependency.verification=strict" not in properties:
        raise ValueError("Strict dependency verification must be explicit")
    return len(expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", action="store_true", help="Fetch all official artifacts and generate review receipts")
    args = parser.parse_args()
    if args.review:
        artifacts = inventory(ROOT / METADATA)
        with ThreadPoolExecutor(max_workers=6) as executor:
            records = list(executor.map(review_artifact, sorted(artifacts.items())))
        (ROOT / RECEIPTS).write_text(json.dumps({"schemaVersion": 1, "method": "SHA-256 of fresh HTTPS repository bytes, independent of the Gradle cache; not publisher signature verification", "artifacts": records}, indent=2) + "\n")
    print(f"Verified review receipts for {validate()} dependency artifacts")


if __name__ == "__main__":
    main()
