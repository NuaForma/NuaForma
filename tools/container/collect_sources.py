"""Retain matching container source inputs; collection is not license audit approval or publication."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import urllib.parse
import urllib.request


def read_url(url, limit=4 * 1024 * 1024):
    assert url.startswith("https://")
    with urllib.request.urlopen(url, timeout=60) as response:
        assert response.url.startswith("https://")
        value = response.read(limit + 1)
        assert len(value) <= limit
        return value


def download(url, file, algorithm, expected):
    assert re.fullmatch(r"[a-f0-9]{40}" if algorithm == "sha1" else r"[a-f0-9]{64}", expected)
    file.parent.mkdir(parents=True, exist_ok=True)
    if not file.exists():
        partial = file.with_name(file.name + ".partial")
        with urllib.request.urlopen(url, timeout=90) as response, partial.open("xb") as output:
            assert response.url.startswith("https://")
            total = 0
            while block := response.read(1024 * 1024):
                total += len(block)
                assert total <= 1024 * 1024 * 1024, "Source file exceeds collection ceiling"
                output.write(block)
        partial.rename(file)
    assert not file.is_symlink()
    digest = hashlib.new(algorithm)
    sha256 = hashlib.sha256()
    with file.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block); sha256.update(block)
    assert digest.hexdigest() == expected, "Source checksum mismatch"
    return {"url": url, "file": file.name, "sha256": sha256.hexdigest(), "bytes": file.stat().st_size}


def source_pairs(inventory):
    records = {}
    for name in ("outer-packages.tsv", "renderer-packages.tsv"):
        for line in (inventory / name).read_text(encoding="utf-8").splitlines():
            binary, binary_version, source, version = line.split("\t")
            assert re.fullmatch(r"[a-z0-9.+-]+", source) and re.fullmatch(r"[A-Za-z0-9.+:~_-]+", version)
            records.setdefault((source, version), []).append({"scope": name, "binary": binary, "version": binary_version})
    return records


def collect_debian(source, version, packages, destination):
    query = "https://snapshot.debian.org/mr/package/" + urllib.parse.quote(source, safe="") + "/" + urllib.parse.quote(version, safe="") + "/srcfiles"
    metadata = json.loads(read_url(query))
    assert metadata["package"] == source and metadata["version"] == version and metadata["result"]
    files = []
    for item in metadata["result"]:
        identity = item["hash"]
        assert re.fullmatch(r"[a-f0-9]{40}", identity)
        info = json.loads(read_url("https://snapshot.debian.org/mr/file/" + identity + "/info"))
        assert info["hash"] == identity and info["result"]
        names = {entry["name"] for entry in info["result"]}
        if len(names) > 1:
            upstream_version = version.split(":")[-1].rsplit("-", 1)[0]
            names = {name for name in names if name.startswith(source + "_" + upstream_version + ".")}
        assert len(names) == 1
        name = names.pop()
        assert name not in (".", "..") and re.fullmatch(r"[A-Za-z0-9._+~%-]+", name)
        path = destination / "debian" / source / version.replace(":", "_") / name
        record = download("https://snapshot.debian.org/file/" + identity, path, "sha1", identity)
        record["path"] = path.relative_to(destination).as_posix()
        record["snapshotSha1"] = identity
        files.append(record)
    assert any(file["file"].endswith(".dsc") for file in files), "Missing Debian source descriptor"
    descriptor = next(file for file in files if file["file"].endswith(".dsc"))
    text = (destination / descriptor["path"]).read_text(encoding="utf-8")
    expected_files = re.findall(r"^ ([a-f0-9]{64}) ([0-9]+) ([^\s]+)$", text, re.MULTILINE)
    assert expected_files, "Missing Debian SHA-256 source checksums"
    actual = {file["file"]: file for file in files}
    for digest, size, name in expected_files:
        assert name in actual and actual[name]["sha256"] == digest and actual[name]["bytes"] == int(size)
    return {"source": source, "version": version, "packages": packages, "files": files, "collected": True,
            "descriptorChecksumsVerified": True}


def collect_upstream(destination):
    records = []
    base = "https://download.documentfoundation.org/libreoffice/src/26.8.1/"
    for prefix in ("libreoffice", "libreoffice-dictionaries", "libreoffice-help", "libreoffice-translations"):
        name = prefix + "-26.8.1.1.tar.xz"
        expected = read_url(base + name + ".sha256").decode("ascii").split()[0]
        path = destination / "libreoffice" / name
        record = download(base + name, path, "sha256", expected)
        record["path"] = path.relative_to(destination).as_posix()
        records.append(record)
        signature = read_url(base + name + ".asc")
        path.with_name(name + ".asc").write_bytes(signature)
    for major, version in ((17, "17.0.20.1"), (25, "25.0.4.1")):
        base = f"https://github.com/adoptium/temurin{major}-binaries/releases/download/jdk-{version}%2B1/"
        name = f"OpenJDK{major}U-jdk-sources_{version}_1.tar.gz"
        expected = read_url(base + name + ".sha256.txt").decode("ascii").split()[0]
        pinned = {17: "21e2a065d244ab048e737f21af5d1fc74daaeb6707de36477ead8db1dca71214",
                  25: "cb9e50d2eb3de72ffd28466d7c511b4039b4bc4bd8f0d276d1e4cf81e30fdf09"}
        assert expected == pinned[major], "Temurin source changed; explicit input review required"
        path = destination / "temurin" / name
        record = download(base + name, path, "sha256", expected)
        record["path"] = path.relative_to(destination).as_posix()
        records.append(record)
        for suffix in (".sig", ".json"):
            path.with_name(name + suffix).write_bytes(read_url(base + name + suffix))
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    destination = args.output.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    image = json.loads((args.inventory / "image.json").read_text(encoding="utf-8"))
    assert re.fullmatch(r"sha256:[a-f0-9]{64}", image["Id"])
    pairs = source_pairs(args.inventory)
    records, errors, upstream_packages = [], [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        jobs = {}
        for (source, version), packages in sorted(pairs.items()):
            if source.startswith(("libobasis26.8-", "libreoffice26.8")):
                assert version == ("26.8.1-1" if source == "libreoffice26.8-debian-menus" else "26.8.1.1-1")
                upstream_packages.extend(packages)
            else:
                jobs[executor.submit(collect_debian, source, version, packages, destination)] = (source, version)
        for future in concurrent.futures.as_completed(jobs):
            source, version = jobs[future]
            try:
                records.append(future.result())
                print("Collected", source, version, flush=True)
            except Exception as error:
                errors.append({"source": source, "version": version, "errorType": type(error).__name__})
                print("Collection failed", source, version, type(error).__name__, flush=True)
    upstream = []
    try:
        upstream = collect_upstream(destination)
    except Exception as error:
        errors.append({"source": "upstream-runtime", "errorType": type(error).__name__})
    report = {"baselineImage": image["Id"], "debian": sorted(records, key=lambda item: (item["source"], item["version"])),
              "upstreamPackages": upstream_packages, "upstream": upstream, "errors": errors,
              "auditApproved": False, "published": False,
              "pending": ["final-candidate inventory comparison", "license/notice mapping", "signature and Debian descriptor verification",
                          "LibreOffice external source/build-resource coverage", "Temurin build provenance", "Maven source/notice inventory",
                          "recipient source access and durable release packaging", "final-candidate fidelity/security qualification"]}
    (destination / "collection.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    assert not errors, "Incomplete collection; inspect the retained report"
    print("Matching source files retained; distribution audit remains unapproved")


if __name__ == "__main__": main()
