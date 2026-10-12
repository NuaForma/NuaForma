"""Stage only reviewed source/notice materials beside the production server, never test inputs."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil


def stage(collection, provenance, application, version):
    assert re.fullmatch(r"0\.1\.0-alpha\.1", version)
    record = json.loads((collection / "inventory.json").read_text())
    assert not record["errors"]
    original = json.loads((provenance / "inventory.json").read_text())
    assert {item["file"] for item in record["components"]} == {file.name for file in (application / "lib").glob("*.jar")}, "Dependency inventory does not cover the actual classpath"
    destination = application / "third-party-materials"
    assert not destination.is_symlink()
    destination.mkdir(exist_ok=True)
    copied = []
    for component in record["components"]:
        jar = application / "lib" / component["file"]
        if component["group"] == "dev.nuaforma":
            assert component["version"] == version and jar.is_file()
        else:
            assert hashlib.sha256(jar.read_bytes()).hexdigest() == component["sha256"]
        for item in component["materials"]:
            relative = pathlib.PurePosixPath(item["path"])
            assert not relative.is_absolute() and ".." not in relative.parts
            assert not str(relative).endswith(".jar") or str(relative).endswith("-sources.jar")
            source = collection / relative
            assert not source.is_symlink() and hashlib.sha256(source.read_bytes()).hexdigest() == item["sha256"]
            target = destination / "application" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                assert not target.is_symlink() and hashlib.sha256(target.read_bytes()).hexdigest() == item["sha256"]
            else:
                shutil.copyfile(source, target)
            copied.append(dict(path=target.relative_to(destination).as_posix(), sha256=item["sha256"]))
    for item in original["files"]:
        if not item["file"].startswith(("docx4j-", "antlr-", "xalan-", "mbassador-")):
            continue
        source = provenance / item["file"]
        assert not source.is_symlink() and hashlib.sha256(source.read_bytes()).hexdigest() == item["sha256"]
        target = destination / item["file"]
        if target.exists():
            assert not target.is_symlink() and hashlib.sha256(target.read_bytes()).hexdigest() == item["sha256"]
        else:
            shutil.copyfile(source, target)
        copied.append(dict(path=item["file"], sha256=item["sha256"]))
    (destination / "inventory.json").write_text(json.dumps(dict(version=version, components=record["components"], retained=copied), indent=2), encoding="utf-8")
    (destination / "README.md").write_text(
        "# Original application dependency material\n\n"
        "The unmodified license, notice, copyright and Maven metadata files under `application` belong to the named upstream JARs. "
        "Matching source JARs retain source-file copyright/license headers even where the binary JAR omitted those texts. "
        "Open a source JAR with any ZIP reader; it is documentation/source material, not on the runtime classpath. "
        "The original docx4j and relocated Xalan LICENSE/NOTICE, MBassador MIT text and ANTLR 2 distribution supplement incomplete artifact-level metadata. "
        "MBassador's MIT text is retained from a pinned historical upstream commit; that commit is not represented as a 1.3.2 release tag. "
        "ANTLR 2 includes BSD-licensed Python-generator code; its unrelated Python runtime is not in the application's binary JAR. "
        "The retained unmodified ANTLR source distribution keeps those original licensing distinctions.\n\n"
        "These materials do not relicense dependencies under NuaForma's Apache-2.0 license. "
        "For OS, renderer, JVM and validator source access, see `/usr/share/nuaforma/SOURCES.md`.\n",
        encoding="utf-8")
    print("Staged", len(copied), "original application source/notice materials")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("collection", "provenance", "application"):
        parser.add_argument("--" + name, required=True, type=pathlib.Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    stage(args.collection, args.provenance, args.application, args.version)
