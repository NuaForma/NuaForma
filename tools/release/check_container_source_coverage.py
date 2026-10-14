"""Check reviewed component identities and anonymous matching sources before binary transfer."""
import argparse
import hashlib
import json
import pathlib
import urllib.request


def verify_inventory(root, platform):
    if set(platform["packages"]) != {"outer-packages.tsv", "renderer-packages.tsv"}:
        raise ValueError("Both package scopes must be reviewed")
    for name, expected in platform["packages"].items():
        if name not in ("outer-packages.tsv", "renderer-packages.tsv") or hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
            raise ValueError("Package inventory differs from source review")
    actual = {}
    for name in ("application-sha256.txt", "validator-sha256.txt", "verapdf-sha256.txt"):
        for line in (root / name).read_text().splitlines():
            digest, path = line.split()
            jar = pathlib.PurePosixPath(path).name
            if not jar.startswith("nuaforma-"):
                actual[name + "/" + jar] = digest
    if actual != platform["foreignJars"]:
        raise ValueError("Third-party runtime JARs differ from source review")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review", type=pathlib.Path, required=True)
    parser.add_argument("--inventory", type=pathlib.Path, required=True)
    parser.add_argument("--architecture", choices=("amd64", "arm64"), required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    record = json.loads(args.review.read_text())
    if record["version"] != args.version or set(record["platforms"]) != {"amd64", "arm64"}:
        raise ValueError("Source review must cover this version and both architectures")
    verify_inventory(args.inventory, record["platforms"][args.architecture])
    if len(record["sourceMaterials"]) != 4:
        raise ValueError("Require all four reviewed source archives")
    prefix = "https://github.com/NuaForma/NuaForma/releases/download/v" + args.version + "/"
    for asset in record["sourceMaterials"]:
        if not asset["url"].startswith(prefix):
            raise ValueError("Source provision must belong to the selected version")
        digest, count = hashlib.sha256(), 0
        with urllib.request.urlopen(asset["url"], timeout=120) as response:
            if not response.url.startswith("https://"):
                raise ValueError("Source transfer must remain HTTPS")
            while block := response.read(1024 * 1024):
                count += len(block)
                if count > 2 * 1024 * 1024 * 1024:
                    raise ValueError("Source asset exceeds transfer bound")
                digest.update(block)
        if not count or digest.hexdigest() != asset["sha256"]:
            raise ValueError("Anonymous matching source retrieval failed")
    print("Reviewed component identities and anonymous exact source provision: PASS")


if __name__ == "__main__":
    main()
