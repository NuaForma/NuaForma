"""Check a reviewed platform archive and loaded config; never run or publish it."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import tarfile
from validate_multiarch_release import APPLICATIONS, PLATFORMS, check


def validate_archive(path, review, architecture):
    version = review["version"]
    check(review, version, review["sourceCommit"])
    platform = review["platforms"][architecture]
    application = review.get("application", "server")
    image_name = APPLICATIONS[application]
    if path.name != f"{image_name}-container-{version}-{architecture}.tar.gz" or not 0 < path.stat().st_size <= 2 * 1024 * 1024 * 1024:
        raise ValueError("Unexpected platform archive")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    if digest.hexdigest() != platform["containerArchiveSha256"]:
        raise ValueError("Archive differs from approved candidate")
    config = None
    with tarfile.open(path, "r|gz") as archive:
        count, expanded = 0, 0
        for member in archive:
            count += 1
            expanded += member.size
            if count > 100_000 or expanded > 8 * 1024 * 1024 * 1024:
                raise ValueError("Archive expansion budget exceeded")
            if member.isfile() and member.size <= 1024 * 1024:
                data = archive.extractfile(member).read()
                if hashlib.sha256(data).hexdigest() == platform["containerConfigSha256"]:
                    config = json.loads(data)
    if config is None or config.get("architecture") != architecture or config.get("os") != "linux":
        raise ValueError("Approved native image configuration missing")
    if config["config"].get("User") != "1000:1000":
        raise ValueError("Non-root runtime required")
    labels = config["config"].get("Labels", {})
    if "application" in review and labels.get("dev.nuaforma.application") != application:
        raise ValueError("Image application differs from review")
    environment = dict(value.split("=", 1) for value in config["config"].get("Env", []) if "=" in value)
    if application == "playground" and any(environment.get(key) != value for key, value in {
        "NUAFORMA_APPLICATION": "playground", "NUAFORMA_PLAYGROUND": "true", "NUAFORMA_MODE": "development"}.items()):
        raise ValueError("Playground startup defaults do not match the reviewed application")
    if labels.get("org.opencontainers.image.version") != version or labels.get("org.opencontainers.image.revision") != review["sourceCommit"]:
        raise ValueError("Image source/version differs from review")
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", type=pathlib.Path, required=True)
    parser.add_argument("--architecture", choices=PLATFORMS, required=True)
    parser.add_argument("--archive", type=pathlib.Path, required=True)
    parser.add_argument("--check-loaded", action="store_true")
    parser.add_argument("--application", choices=APPLICATIONS, default="server")
    args = parser.parse_args()
    review = json.loads(args.review.read_text())
    if review.get("application", "server") != args.application:
        raise ValueError("Review belongs to a different application")
    config = validate_archive(args.archive, review, args.architecture)
    if args.check_loaded:
        tag = f'{APPLICATIONS[args.application]}:{review["version"]}-{args.architecture}-archive'
        image = json.loads(subprocess.check_output(["docker", "image", "inspect", tag], text=True, timeout=30))[0]
        if image["Id"] != "sha256:" + review["platforms"][args.architecture]["containerConfigSha256"]:
            raise ValueError("Loaded configuration identity differs from approved archive")
        if image["Os"] != "linux" or image["Architecture"] != args.architecture or image["RootFS"]["Layers"] != config["rootfs"]["diff_ids"]:
            raise ValueError("Loaded platform layers differ from approved candidate")
        for key, value in config["config"].items():
            if image["Config"].get(key) != value:
                raise ValueError("Loaded runtime configuration differs from archive")
    print("Exact reviewed " + args.architecture + " image identity: PASS")


if __name__ == "__main__":
    main()
