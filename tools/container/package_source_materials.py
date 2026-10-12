"""Create allowlisted, checksum-bound source archives locally; never publish or include container binaries."""
import argparse
import hashlib
import json
import pathlib
import tarfile


def checksum(file):
    value = hashlib.sha256()
    with file.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            value.update(block)
    return value.hexdigest()


def add_record(entries, root, path, digest, prefix):
    relative = pathlib.PurePosixPath(path)
    assert not relative.is_absolute() and ".." not in relative.parts
    file = root / relative
    assert not file.is_symlink() and file.is_file() and checksum(file) == digest
    name = prefix + "/" + relative.as_posix()
    assert name not in entries
    entries[name] = (file, digest)


def package(args):
    assert args.version == "0.1.0-alpha.1"
    args.output.mkdir(parents=True, exist_ok=False)
    sources = json.loads((args.sources / "collection.json").read_text())
    externals = json.loads((args.externals / "collection.json").read_text())
    provenance = json.loads((args.provenance / "inventory.json").read_text())
    application = json.loads((args.application / "inventory.json").read_text())
    assert not sources["errors"] and not externals["errors"]
    groups = {name: {} for name in ("debian", "libreoffice", "java", "jvm")}
    for source in sources["debian"]:
        assert source["descriptorChecksumsVerified"]
        for file in source["files"]:
            add_record(groups["debian"], args.sources, file["path"], file["sha256"], "sources")
    for file in sources["upstream"]:
        name = "libreoffice" if file["path"].startswith("libreoffice/") else "java"
        add_record(groups[name], args.sources, file["path"], file["sha256"], "sources")
        for suffix in (".asc", ".sig", ".json"):
            candidate = args.sources / (file["path"] + suffix)
            if candidate.is_file():
                add_record(groups[name], args.sources, file["path"] + suffix, checksum(candidate), "sources")
    excluded = []
    for file in externals["files"]:
        if file["file"].endswith(".nupkg"):
            assert file["file"] == "python.3.12.8.nupkg"
            excluded.append(dict(file=file["file"], reason="unused Windows-only bootstrap binary"))
            continue
        add_record(groups["libreoffice"], args.externals, file["file"], file["sha256"], "external-inputs")
    add_record(groups["libreoffice"], args.externals, "download.lst", checksum(args.externals / "download.lst"), "external-inputs")
    for file in provenance["files"]:
        name = "java" if file["file"].startswith("temurin-build-") else "jvm"
        add_record(groups[name], args.provenance, file["file"], file["sha256"], "upstream-provenance")
    for file in application["retained"]:
        add_record(groups["jvm"], args.application, file["path"], file["sha256"], "application-materials")
    for file in args.validator.rglob("*"):
        if file.is_file():
            assert not file.is_symlink()
            relative = file.relative_to(args.validator).as_posix()
            assert not relative.endswith(".jar") or relative.endswith("-sources.jar")
            add_record(groups["jvm"], args.validator, relative, checksum(file), "validator-materials")
    records = []
    for name, entries in groups.items():
        destination = args.output / ("nuaforma-sources-" + name + "-" + args.version + ".tar")
        with tarfile.open(destination, "x", format=tarfile.PAX_FORMAT) as archive:
            for path, (file, digest) in sorted(entries.items()):
                info = tarfile.TarInfo(path)
                info.size, info.mode, info.mtime = file.stat().st_size, 0o644, 0
                with file.open("rb") as stream:
                    archive.addfile(info, stream)
            manifest = json.dumps(dict(files=[dict(path=path, sha256=digest) for path, (_, digest) in sorted(entries.items())],
                                       excluded=excluded if name == "libreoffice" else []), indent=2).encode()
            import io
            info = tarfile.TarInfo("SOURCE-MANIFEST.json")
            info.size, info.mode, info.mtime = len(manifest), 0o644, 0
            archive.addfile(info, io.BytesIO(manifest))
        assert destination.stat().st_size < 2 * 1024 * 1024 * 1024
        records.append(dict(file=destination.name, sha256=checksum(destination), bytes=destination.stat().st_size, inputs=len(entries)))
        print(destination.name, len(entries), flush=True)
    (args.output / "source-assets.json").write_text(json.dumps(dict(version=args.version, assets=records, published=False), indent=2), encoding="utf-8")
    (args.output / "SHA256SUMS.sources").write_text("".join(file["sha256"] + "  " + file["file"] + "\n" for file in records), encoding="ascii")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sources", "externals", "provenance", "application", "validator", "output"):
        parser.add_argument("--" + name, required=True, type=pathlib.Path)
    parser.add_argument("--version", required=True)
    package(parser.parse_args())
