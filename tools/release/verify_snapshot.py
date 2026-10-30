"""Retrieve timestamped snapshots anonymously and compare every signed Maven artifact."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import xml.etree.ElementTree as ET

from maven_destination import SNAPSHOTS, destination, require
from nexus_repository import request


def resolved_versions(metadata, version):
    require(len(metadata) <= 1024 * 1024 and b"<!DOCTYPE" not in metadata.upper()
            and b"<!ENTITY" not in metadata.upper(), "Unsafe snapshot metadata")
    root = ET.fromstring(metadata)
    require(root.tag == "metadata" and root.findtext("version") == version, "Snapshot metadata version differs")
    values = {}
    variants = [("pom", "", ".pom"), ("jar", "", ".jar"), ("jar", "sources", "-sources.jar"), ("jar", "javadoc", "-javadoc.jar")]
    if any(item.findtext("extension") == "module" for item in root.findall("./versioning/snapshotVersions/snapshotVersion")):
        variants.append(("module", "", ".module"))
    for extension, classifier, suffix in variants:
        choices = [item.findtext("value") for item in root.findall("./versioning/snapshotVersions/snapshotVersion")
                   if item.findtext("extension") == extension and (item.findtext("classifier") or "") == classifier]
        require(len(choices) == 1 and re.fullmatch(re.escape(version[:-9]) + r"-\d{8}\.\d{6}-[1-9][0-9]*", choices[0] or ""),
                "Require one timestamped snapshot value for each Maven artifact")
        values[suffix] = choices[0]
    require(len(set(values.values())) == 1, "Snapshot metadata mixes publication generations")
    return values


def verify(repository, version, modules):
    require(destination(version)["kind"] == "snapshot", "Require canonical X.Y.Z-SNAPSHOT")
    hashes = {}
    for module in modules:
        relative = f"dev/nuaforma/{module}/{version}/"
        metadata, _ = request(relative + "maven-metadata.xml", repository=SNAPSHOTS)
        remote = resolved_versions(metadata, version)
        local_folder = repository / relative
        local_metadata = local_folder / "maven-metadata.xml"
        local = resolved_versions(local_metadata.read_bytes(), version)
        require(set(remote) == set(local), "Remote snapshot omitted or added a publication variant")
        for suffix in remote:
            source = local_folder / (module + "-" + version + suffix)
            if not source.is_file():
                source = local_folder / (module + "-" + local[suffix] + suffix)
            for signature in ("", ".asc"):
                file = pathlib.Path(str(source) + signature)
                require(file.is_file() and not file.is_symlink(), "Require the local signed snapshot artifacts")
                name = relative + module + "-" + remote[suffix] + suffix + signature
                data, _ = request(name, repository=SNAPSHOTS)
                digest = hashlib.sha256(file.read_bytes()).hexdigest()
                require(hashlib.sha256(data).hexdigest() == digest, "Anonymous snapshot differs from the locally signed publication; inspect before retrying")
                hashes[name] = digest
    return {"version": version, "repository": SNAPSHOTS, "publicBytesMatch": True, "sha256": hashes,
            "sourceCommit": os.environ.get("GITHUB_SHA"), "workflowRun": os.environ.get("GITHUB_RUN_ID")}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=pathlib.Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--modules", nargs="+", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    result = verify(args.repository, args.version, args.modules)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as file:
        file.write(json.dumps(result, indent=2) + "\n")
