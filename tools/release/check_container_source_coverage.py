"""Check reviewed component identities and anonymous matching sources before binary transfer."""
import argparse
import hashlib
import json
import pathlib
import urllib.request


def reviewed_asset_tag(record, version):
    tag = record.get("assetTag", "v" + version)
    if tag not in {"v" + version, "sources-v" + version}:
        raise ValueError("Unexpected source-only release tag")
    return tag


def verify_inventory(root, platform):
    java = root / "java-sha256.txt"
    image = root / "image.json"
    labels = json.loads(image.read_text()).get("Labels", {}) if image.is_file() else {}
    split_runtime = labels.get("dev.nuaforma.jvm-layout") == "server25-validator17"
    if split_runtime or java.exists() or "javaRuntimeFilesSha256" in platform:
        if not java.is_file() or hashlib.sha256(java.read_bytes()).hexdigest() != platform.get("javaRuntimeFilesSha256"):
            raise ValueError("Both Java runtime files must match the exact source/license review")
    scopes = {"outer-packages.tsv"} if labels.get("dev.nuaforma.validation-boundary") == "container" else {"outer-packages.tsv", "renderer-packages.tsv"}
    if set(platform["packages"]) != scopes:
        raise ValueError("Every package scope of this exact image boundary must be reviewed")
    for name, expected in platform["packages"].items():
        if name not in scopes or hashlib.sha256((root / name).read_bytes()).hexdigest() != expected:
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
    parser.add_argument("--application", choices=("server", "playground"), default="server")
    args = parser.parse_args()
    record = json.loads(args.review.read_text())
    if record.get("application", "server") != args.application:
        raise ValueError("Source review belongs to a different application")
    if "application" in record:
        image = json.loads((args.inventory / "image.json").read_text())
        if image.get("Labels", {}).get("dev.nuaforma.application") != args.application:
            raise ValueError("Image application differs from source review")
    if record["version"] != args.version or set(record["platforms"]) != {"amd64", "arm64"}:
        raise ValueError("Source review must cover this version and both architectures")
    if args.version not in ("0.1.0-alpha.1", "0.1.0-alpha.2", "0.1.0-alpha.3"):
        if not (args.inventory / "java-sha256.txt").is_file() or "javaRuntimeFilesSha256" not in record["platforms"][args.architecture]:
            raise ValueError("New releases require exact review of both JVM installations")
    verify_inventory(args.inventory, record["platforms"][args.architecture])
    boundary = json.loads((args.inventory / "image.json").read_text()).get("Labels", {}).get("dev.nuaforma.validation-boundary")
    required_groups = {"debian", "java", "jvm"} if boundary == "container" else {"debian", "libreoffice", "java", "jvm"}
    required_files = {f"nuaforma-sources-{group}-{args.version}.tar" for group in required_groups}
    actual_files = {asset["url"].rsplit("/", 1)[-1] for asset in record["sourceMaterials"]}
    if len(record["sourceMaterials"]) != len(required_files) or actual_files != required_files:
        raise ValueError("Require every reviewed source archive of the exact runtime boundary")
    prefix = "https://github.com/NuaForma/NuaForma/releases/download/" + reviewed_asset_tag(record, args.version) + "/"
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
