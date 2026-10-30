"""Explicit Maven version routing, shared by preparation and publication gates."""
import argparse
import json
import re

CENTRAL = "https://repo.maven.apache.org/maven2/"
PRERELEASES = "https://repo.nuaforma.dev/repository/nuaforma-prereleases/"
SNAPSHOTS = "https://repo.nuaforma.dev/repository/nuaforma-snapshots/"
NUMBER = r"(?:0|[1-9][0-9]*)"
BASE = NUMBER + r"\." + NUMBER + r"\." + NUMBER
IDENTIFIER = r"(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def destination(version):
    require(isinstance(version, str), "An explicit Maven version is required")
    if re.fullmatch(BASE + r"-SNAPSHOT", version):
        return {"kind": "snapshot", "repository": SNAPSHOTS}
    require(re.fullmatch(BASE + r"(?:-" + IDENTIFIER + r"(?:\." + IDENTIFIER + r")*)?", version),
            "Require X.Y.Z, a SemVer prerelease, or canonical X.Y.Z-SNAPSHOT")
    require("SNAPSHOT" not in version.upper(), "Snapshots must use canonical X.Y.Z-SNAPSHOT")
    if "-" in version:
        return {"kind": "prerelease", "repository": PRERELEASES}
    return {"kind": "stable", "repository": CENTRAL}


def stable_version(version):
    require(destination(version)["kind"] == "stable", "Maven Central accepts stable X.Y.Z releases only; use Nexus for development versions")
    return version


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--central", action="store_true")
    args = parser.parse_args()
    if args.central:
        stable_version(args.version)
    print(json.dumps(destination(args.version), sort_keys=True))
