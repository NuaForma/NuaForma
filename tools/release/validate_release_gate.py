"""Require reviewed candidate evidence before exposing upload credentials or distributing binaries."""
import argparse
import json
import pathlib
import re
import subprocess
import hashlib
import urllib.request

from prepare_central_bundle import release_version
from verify_libraries import MODULES


def check(record, version, commit):
    assert release_version(version) == "0.1.0-alpha.1"
    assert record["version"] == version and record["sourceCommit"] == commit
    assert re.fullmatch(r"[a-f0-9]{40}", commit)
    assert re.fullmatch(r"sha256:[a-f0-9]{64}", record["qualifiedImage"])
    assert re.fullmatch(r"[a-f0-9]{64}", record["containerArchiveSha256"])
    assert re.fullmatch(r"[a-f0-9]{64}", record["containerConfigSha256"])
    for name in ("libraryAudit", "containerAudit", "sourceAccess", "fidelityQualification", "securityReview"):
        assert record["approved"][name] is True, "Unapproved release condition: " + name
    assert record["reviewer"] == "mrhoeve"
    assert record["latest"] is False
    assert record["sourceMaterials"], "Missing matching source provision"
    prefix = "https://github.com/NuaForma/NuaForma/releases/download/v" + version + "/"
    for asset in record["sourceMaterials"]:
        assert asset["url"].startswith(prefix) and re.fullmatch(r"[a-f0-9]{64}", asset["sha256"])
        assert asset["verifiedAnonymousAccess"] is True
    expected = {module + "-" + version + suffix for module in MODULES for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")}
    assert set(record["libraryHashes"]) == expected
    assert all(re.fullmatch(r"[a-f0-9]{64}", value) for value in record["libraryHashes"].values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--review", type=pathlib.Path, required=True)
    args = parser.parse_args()
    version = release_version(args.version)
    tag = "refs/tags/v" + version
    assert args.review.is_file(), "Missing signed-review source record; publication remains blocked"
    commit = subprocess.check_output(["git", "rev-parse", "--verify", tag + "^{commit}"], text=True).strip()
    assert subprocess.check_output(["git", "cat-file", "-t", tag], text=True).strip() == "tag", "Require an annotated release tag"
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, "origin/main"], check=True)
    tag_object = subprocess.check_output(["git", "rev-parse", tag], text=True).strip()
    verified = subprocess.check_output(["gh", "api", "repos/NuaForma/NuaForma/git/tags/" + tag_object,
                                       "--jq", ".verification.verified"], text=True).strip()
    assert verified == "true", "Require a verified signed release tag"
    record = json.loads(args.review.read_text(encoding="utf-8"))
    check(record, version, commit)
    for asset in record["sourceMaterials"]:
        digest = hashlib.sha256()
        total = 0
        with urllib.request.urlopen(asset["url"], timeout=120) as response:
            assert response.url.startswith("https://")
            while block := response.read(1024 * 1024):
                total += len(block)
                assert total <= 2 * 1024 * 1024 * 1024, "Source asset exceeds bound"
                digest.update(block)
        assert total > 0 and digest.hexdigest() == asset["sha256"], "Anonymous source asset differs from review"
    print("Reviewed release conditions and signed source tag match")


if __name__ == "__main__": main()
