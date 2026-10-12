"""Bind archive, loaded Docker image and source labels to the approved candidate; never push."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import tarfile


def check(file, review):
    assert file.name == "nuaforma-container-0.1.0-alpha.1.tar.gz"
    assert 0 < file.stat().st_size <= 2 * 1024 * 1024 * 1024
    digest = hashlib.sha256()
    with file.open("rb") as source:
        while block := source.read(1024 * 1024): digest.update(block)
    assert digest.hexdigest() == review["containerArchiveSha256"], "Container archive differs from review"
    with tarfile.open(file, "r|gz") as archive:
        config = None
        for member in archive:
            if member.isfile() and member.size <= 1024 * 1024:
                data = archive.extractfile(member).read()
                if hashlib.sha256(data).hexdigest() == review["containerConfigSha256"]:
                    config = json.loads(data)
                    break
        assert config is not None and config["os"] == "linux" and config["architecture"] == "amd64"
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", type=pathlib.Path, required=True)
    parser.add_argument("--archive", type=pathlib.Path, required=True)
    parser.add_argument("--check-loaded", action="store_true")
    args = parser.parse_args()
    review = json.loads(args.review.read_text())
    config = check(args.archive, review)
    if args.check_loaded:
        image = json.loads(subprocess.check_output(["docker", "image", "inspect", "nuaforma:0.1.0-alpha.1-archive"], text=True))[0]
        assert image["Os"] == "linux" and image["Architecture"] == "amd64"
        assert image["RootFS"]["Layers"] == config["rootfs"]["diff_ids"]
        for key, value in config["config"].items():
            assert image["Config"].get(key) == value, "Loaded runtime configuration differs from audited archive"
        assert image["Config"]["User"] == "1000:1000"
        labels = image["Config"].get("Labels", {})
        assert labels["org.opencontainers.image.revision"] == review["sourceCommit"]
        assert labels["org.opencontainers.image.version"] == review["version"]
    print("Exact reviewed container archive/image identity matches")


if __name__ == "__main__": main()
