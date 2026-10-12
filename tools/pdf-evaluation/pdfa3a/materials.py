"""Retain exact CLI identities, embedded notices/profiles and corresponding Maven source artifacts."""
import argparse
import hashlib
import json
import pathlib
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=pathlib.Path)
    parser.add_argument("destination", type=pathlib.Path)
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=False)
    records = []
    coordinates = set()
    with zipfile.ZipFile(args.jar) as jar:
        for name in jar.namelist():
            if name.endswith("pom.properties"):
                values = dict(line.split("=", 1) for line in jar.read(name).decode().splitlines() if "=" in line and not line.startswith("#"))
                coordinates.add((values["groupId"], values["artifactId"], values["version"]))
            if name.endswith("pom.xml") or (not name.endswith("/") and any(token in name.lower() for token in ("license", "notice", "copyright")) and not name.endswith(".class")) or name.endswith("PDFA-3A.xml") or name.endswith("PDFA-3B.xml"):
                path = args.destination / "embedded" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(jar.read(name))
    # Exact versions independently read from the signed CLI class constants / embedded build POMs.
    coordinates.update({("net.sf.saxon", "Saxon-HE", "12.8"), ("org.xmlresolver", "xmlresolver", "5.3.3"), ("com.beust", "jcommander", "1.81"),
                        ("org.mozilla", "rhino", "1.7.15.1"), ("net.java.dev.stax-utils", "stax-utils", "20070216")})
    for group, artifact, version in sorted(coordinates):
        base = "https://repo.maven.apache.org/maven2/" + group.replace(".", "/") + "/" + artifact + "/" + version + "/" + artifact + "-" + version
        record = {"group": group, "artifact": artifact, "version": version, "files": []}
        for suffix in (".pom", "-sources.jar"):
            url = base + suffix
            target = args.destination / "sources" / group / artifact / version / (artifact + "-" + version + suffix)
            target.parent.mkdir(parents=True, exist_ok=True)
            try:
                with urllib.request.urlopen(url, timeout=30) as response:
                    data = response.read(32 * 1024 * 1024 + 1)
                assert len(data) <= 32 * 1024 * 1024
                target.write_bytes(data)
                record["files"].append({"url": url, "sha256": hashlib.sha256(data).hexdigest(), "path": str(target.relative_to(args.destination))})
            except Exception as failure:
                record["files"].append({"url": url, "error": str(failure)})
        records.append(record)
        print(group + ":" + artifact + ":" + version, flush=True)
    (args.destination / "inventory.json").write_text(json.dumps({"jarSha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(), "components": records}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
