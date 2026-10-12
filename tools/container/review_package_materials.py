"""Bind retained package notices and exact source inputs to a container inventory, offline."""
import argparse
import hashlib
import json
import pathlib
import posixpath
import re
import tarfile


def digest(file):
    value = hashlib.sha256()
    with file.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            value.update(block)
    return value.hexdigest()


def read_member(archive, name):
    visited = set()
    for _ in range(16):
        if name in visited:
            raise ValueError("Cyclic notice link")
        visited.add(name)
        try:
            member = archive.getmember(name)
        except KeyError:
            parts = pathlib.PurePosixPath(name).parts
            for index in range(1, len(parts)):
                parent = "/".join(parts[:index])
                try:
                    link = archive.getmember(parent)
                except KeyError:
                    continue
                if link.issym():
                    if link.linkname.startswith("/"):
                        raise ValueError("Absolute parent notice link")
                    name = posixpath.normpath(posixpath.join(posixpath.dirname(parent), link.linkname, *parts[index:]))
                    if name.startswith(("../", "/")) or name == "..":
                        raise ValueError("Notice link escapes archive")
                    break
            else:
                raise
            continue
        if member.isfile():
            if member.size > 4 * 1024 * 1024:
                raise ValueError("Oversized notice")
            return archive.extractfile(member).read()
        if not member.issym() and not member.islnk():
            raise ValueError("Notice is not a file")
        if member.linkname.startswith("/"):
            raise ValueError("Absolute notice link requires separate common-license review")
        name = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname)) if member.issym() else member.linkname
        if name.startswith(("../", "/")) or name == "..":
            raise ValueError("Notice link escapes archive")
    raise ValueError("Notice link budget exceeded")


def review(inventory, notices, sources, output):
    image = json.loads((inventory / "image.json").read_text())
    legal = json.loads((notices / "inventory.json").read_text())
    collected = json.loads((sources / "collection.json").read_text())
    assert image["Id"] == legal["image"]
    assert not collected["errors"]
    for item in legal["files"]:
        assert digest(notices / item["path"]) == item["sha256"]
    source_map = {}
    for item in collected["debian"]:
        assert item["descriptorChecksumsVerified"]
        for file in item["files"]:
            assert digest(sources / file["path"]) == file["sha256"]
        source_map[item["source"], item["version"]] = item
    for file in collected["upstream"]:
        assert digest(sources / file["path"]) == file["sha256"]
    output.mkdir(parents=True, exist_ok=False)
    records, gaps = [], []
    for scope in ("outer", "renderer"):
        with tarfile.open(notices / (scope + "-doc.tar")) as archive:
            for line in (inventory / (scope + "-packages.tsv")).read_text().splitlines():
                binary, version, source, source_version = line.split("\t")
                package = binary.split(":")[0]
                record = dict(scope=scope, package=binary, version=version, source=source, sourceVersion=source_version)
                if source.startswith(("libobasis26.8-", "libreoffice26.8")):
                    assert source_version == ("26.8.1-1" if source == "libreoffice26.8-debian-menus" else "26.8.1.1-1")
                    record["sourceFiles"] = [file["path"] for file in collected["upstream"] if file["path"].startswith("libreoffice/")]
                    record["noticeArchive"] = "libreoffice-LICENSE.tar"
                    record["noticeReview"] = "shared upstream license; bundled subcomponents require separate coverage"
                else:
                    if (source, source_version) not in source_map:
                        gaps.append(dict(scope=scope, package=binary, reason="missing exact source"))
                    else:
                        record["sourceFiles"] = [file["path"] for file in source_map[source, source_version]["files"]]
                    name = "doc/" + package + "/copyright"
                    try:
                        data = read_member(archive, name)
                        target = output / scope / (package + ".copyright")
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(data)
                        record["notice"] = target.relative_to(output).as_posix()
                        record["noticeSha256"] = hashlib.sha256(data).hexdigest()
                        text = data.decode("utf-8", errors="replace")
                        record["declaredLicenseLabels"] = sorted(set(re.findall(r"^License:\s*([^\r\n]+)", text, re.MULTILINE)))
                        record["commonLicenseReferences"] = sorted(set(re.findall(r"/usr/share/common-licenses/([A-Za-z0-9.+_-]+)", text)))
                    except (ValueError, KeyError) as error:
                        gaps.append(dict(scope=scope, package=binary, reason=str(error)))
                records.append(record)
    result = dict(image=image["Id"], packages=records, gaps=gaps, auditApproved=False,
                  pending=["review retained license texts and source obligations", "LibreOffice bundled/build inputs", "JVM and JAR coverage", "durable recipient access", "final immutable candidate binding"])
    (output / "review.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(dict(packages=len(records), gaps=gaps, auditApproved=False)))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("inventory", "notices", "sources", "output"):
        parser.add_argument("--" + name, required=True, type=pathlib.Path)
    args = parser.parse_args()
    review(args.inventory, args.notices, args.sources, args.output)
