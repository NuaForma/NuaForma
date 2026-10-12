"""Retain exact server JAR notices, POMs and available sources without uploading binaries."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def fetch(url):
    with urllib.request.urlopen(url, timeout=45) as response:
        data = response.read(64 * 1024 * 1024 + 1)
    assert len(data) <= 64 * 1024 * 1024
    return data


def collect(jar, coordinate, output):
    group, artifact, version = coordinate
    folder = output / group / artifact / version
    folder.mkdir(parents=True, exist_ok=False)
    record = dict(group=group, artifact=artifact, version=version, file=jar.name,
                  sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), materials=[], licenses=[])
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.endswith("/") or name.lower().endswith(".class"):
                continue
            if not any(token in name.lower() for token in ("license", "notice", "copyright", "pom.xml", "pom.properties")):
                continue
            assert not name.startswith("/") and ".." not in pathlib.PurePosixPath(name).parts
            assert archive.getinfo(name).file_size <= 4 * 1024 * 1024
            target = folder / "embedded" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            data = archive.read(name)
            target.write_bytes(data)
            record["materials"].append(dict(path=target.relative_to(output).as_posix(), sha256=hashlib.sha256(data).hexdigest()))
    if group == "dev.nuaforma":
        record["sourceProvision"] = "NuaForma signed release tag and source JARs"
        return record
    upstream_url = "https://repo.maven.apache.org/maven2/" + group.replace(".", "/") + "/" + artifact + "/" + version + "/" + jar.name
    upstream = fetch(upstream_url)
    assert hashlib.sha256(upstream).hexdigest() == record["sha256"], "Runtime JAR differs from the upstream coordinate"
    record["upstreamBinary"] = dict(url=upstream_url, sha256=record["sha256"], bytesVerified=True)
    chain = set()
    current = coordinate
    for _ in range(12):
        assert current not in chain
        chain.add(current)
        g, a, v = current
        assert all(part and "/" not in part and ".." not in part for part in current)
        url = "https://repo.maven.apache.org/maven2/" + g.replace(".", "/") + "/" + a + "/" + v + "/" + a + "-" + v + ".pom"
        data = fetch(url)
        target = folder / "poms" / (g + "." + a + "-" + v + ".pom")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        record["materials"].append(dict(url=url, path=target.relative_to(output).as_posix(), sha256=hashlib.sha256(data).hexdigest()))
        pom = ET.fromstring(data)
        licenses = pom.findall("m:licenses/m:license", NS)
        if licenses:
            record["licenses"] = [dict(name=element.findtext("m:name", default="", namespaces=NS),
                                       url=element.findtext("m:url", default="", namespaces=NS)) for element in licenses]
            break
        parent = pom.find("m:parent", NS)
        if parent is None:
            break
        current = tuple(parent.findtext("m:" + key, namespaces=NS) for key in ("groupId", "artifactId", "version"))
    base = "https://repo.maven.apache.org/maven2/" + group.replace(".", "/") + "/" + artifact + "/" + version + "/" + artifact + "-" + version
    try:
        data = fetch(base + "-sources.jar")
        target = folder / (artifact + "-" + version + "-sources.jar")
        target.write_bytes(data)
        record["materials"].append(dict(url=base + "-sources.jar", path=target.relative_to(output).as_posix(), sha256=hashlib.sha256(data).hexdigest()))
        record["sourceProvision"] = "matching upstream source JAR retained; review completeness by license"
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        record["sourceProvision"] = "no Central source JAR; determine obligation from actual license"
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=pathlib.Path)
    parser.add_argument("--application", type=pathlib.Path, required=True)
    parser.add_argument("--cache", type=pathlib.Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    hashes = ({pathlib.PurePosixPath(line.split(None, 1)[1].strip()).name: line.split()[0]
               for line in args.inventory.read_text().splitlines()} if args.inventory else
              {file.name: hashlib.sha256(file.read_bytes()).hexdigest() for file in args.application.glob("*.jar")})
    assert hashes
    cached = {}
    for jar in args.cache.glob("*/*/*/*/*.jar"):
        if jar.name in hashes:
            cached.setdefault(jar.name, []).append(jar)
    components = []
    for name, expected in hashes.items():
        jar = args.application / name
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == expected, name
        if name.startswith("nuaforma-"):
            coordinate = ("dev.nuaforma", name.removesuffix("-" + args.version + ".jar"), args.version)
        else:
            matches = [path for path in cached.get(name, []) if hashlib.sha256(path.read_bytes()).hexdigest() == expected]
            coordinates = {tuple(path.relative_to(args.cache).parts[:3]) for path in matches}
            assert len(coordinates) == 1, name
            coordinate = coordinates.pop()
        components.append((jar, coordinate))
    records, errors = [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        jobs = {executor.submit(collect, jar, coordinate, args.output): jar.name for jar, coordinate in components}
        for future in concurrent.futures.as_completed(jobs):
            try:
                records.append(future.result())
            except Exception as error:
                errors.append(dict(file=jobs[future], errorType=type(error).__name__))
    (args.output / "inventory.json").write_text(json.dumps(dict(components=sorted(records, key=lambda r: r["file"]), errors=errors, auditApproved=False), indent=2), encoding="utf-8")
    print(json.dumps(dict(components=len(records), errors=errors, auditApproved=False)))
    assert not errors


if __name__ == "__main__":
    main()
