"""Gate future two-platform releases on exact signed-source and per-image review."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import urllib.request

PLATFORMS = ("amd64", "arm64")
APPLICATIONS = {"server": "nuaforma", "playground": "nuaforma-playground"}
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?")
SHA256 = re.compile(r"[a-f0-9]{64}")


def check(record, version, commit):
    if not VERSION.fullmatch(version) or version == "0.1.0-alpha.1":
        raise ValueError("A new explicit version is required; historical alpha is immutable")
    if record.get("application", "server") not in APPLICATIONS:
        raise ValueError("Unknown reviewed application")
    if not re.fullmatch(r"[a-f0-9]{40}", commit) or record.get("sourceCommit") != commit or record.get("version") != version:
        raise ValueError("Review must match signed source and selected release version")
    if record.get("reviewer") != "mrhoeve" or record.get("latest") is not ("-" not in version):
        raise ValueError("Maintainer approval and stable/prerelease alias policy required")
    if set(record.get("platforms", {})) != set(PLATFORMS):
        raise ValueError("Both amd64 and arm64 approved records are required")
    prefix = "https://github.com/NuaForma/NuaForma/releases/download/v" + version + "/"
    for architecture in PLATFORMS:
        platform = record["platforms"][architecture]
        for name in ("containerAudit", "sourceAccess", "fidelityQualification", "securityReview"):
            if platform.get("approved", {}).get(name) is not True:
                raise ValueError("Unapproved " + architecture + " release condition: " + name)
        if platform.get("nativeQualificationArchitecture") != architecture:
            raise ValueError("Native qualification evidence required for each architecture")
        for name in ("containerArchiveSha256", "containerConfigSha256"):
            if not SHA256.fullmatch(platform.get(name, "")):
                raise ValueError("Missing exact " + architecture + " archive/config identity")
        if not platform.get("sourceMaterials"):
            raise ValueError("Matching source provision required for " + architecture)
        for asset in platform["sourceMaterials"]:
            if not asset.get("url", "").startswith(prefix) or not SHA256.fullmatch(asset.get("sha256", "")) or asset.get("verifiedAnonymousAccess") is not True:
                raise ValueError("Unreviewed source access for " + architecture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--review", type=pathlib.Path, required=True)
    parser.add_argument("--application", choices=APPLICATIONS, default="server")
    parser.add_argument("--companion-review", type=pathlib.Path)
    args = parser.parse_args()
    if not VERSION.fullmatch(args.version) or args.version == "0.1.0-alpha.1":
        raise ValueError("Invalid or historical version")
    tag = "refs/tags/v" + args.version
    commit = subprocess.check_output(["git", "rev-parse", "--verify", tag + "^{commit}"], text=True).strip()
    if subprocess.check_output(["git", "cat-file", "-t", tag], text=True).strip() != "tag":
        raise ValueError("Require an annotated release tag")
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, "origin/main"], check=True)
    tag_object = subprocess.check_output(["git", "rev-parse", tag], text=True).strip()
    verified = subprocess.check_output(["gh", "api", "repos/NuaForma/NuaForma/git/tags/" + tag_object,
                                       "--jq", ".verification.verified"], text=True).strip()
    if verified != "true":
        raise ValueError("Require verified source signature")
    record = json.loads(args.review.read_text())
    if record.get("application", "server") != args.application:
        raise ValueError("Review belongs to a different application")
    check(record, args.version, commit)
    records = [record]
    if args.companion_review:
        companion = json.loads(args.companion_review.read_text())
        if companion.get("application") not in APPLICATIONS or companion.get("application") == args.application:
            raise ValueError("Companion must review the other application")
        check(companion, args.version, commit)
        records.append(companion)
    checked = set()
    for platform in (platform for item in records for platform in item["platforms"].values()):
        for asset in platform["sourceMaterials"]:
            identity = asset["url"], asset["sha256"]
            if identity in checked:
                continue
            checked.add(identity)
            digest, count = hashlib.sha256(), 0
            with urllib.request.urlopen(asset["url"], timeout=120) as response:
                if not response.url.startswith("https://"):
                    raise ValueError("Source transfer must remain HTTPS")
                while block := response.read(1024 * 1024):
                    count += len(block)
                    if count > 2 * 1024 * 1024 * 1024:
                        raise ValueError("Source asset exceeds bound")
                    digest.update(block)
            if not count or digest.hexdigest() != asset["sha256"]:
                raise ValueError("Source material differs from review")
    print("Both native platforms, signed source and anonymous matching sources: PASS")


if __name__ == "__main__":
    main()
