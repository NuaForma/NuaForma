"""Retain matching sources, POMs, original embedded notices and exact validation profiles for review."""
import argparse
import hashlib
import json
import pathlib
import urllib.request
import zipfile


def verify_validator_classes(runtime):
    """The isolated validator needs one javax API generation, regardless of Maven artifact names."""
    required = ("javax/xml/bind/JAXBContext.class", "javax/activation/DataHandler.class")
    forbidden = ("jakarta/xml/bind/JAXBContext.class", "jakarta/activation/DataHandler.class")
    owners = {name: [] for name in required}
    class_owners = {}
    for binary in runtime.glob("*.jar"):
        with zipfile.ZipFile(binary) as jar:
            names = set(jar.namelist())
            for name in names:
                if name.endswith(".class") and not name.endswith("module-info.class"):
                    class_owners.setdefault(name, []).append(binary.name)
            assert not names.intersection(forbidden), "The isolated validator must retain javax APIs: " + binary.name
            for name in required:
                if name in names:
                    owners[name].append(binary.name)
    for name, files in owners.items():
        assert len(files) == 1, "Expected exactly one validator API owner for " + name + ": " + ", ".join(files)
    duplicates = sorted(name for name, files in class_owners.items() if len(files) > 1)
    assert not duplicates, "Duplicate validator class entries: " + ", ".join(duplicates[:3])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=pathlib.Path)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    verify_validator_classes(runtime)
    components = json.loads((runtime / "components.json").read_text(encoding="utf-8"))
    destination = runtime / "materials"
    destination.mkdir(parents=True, exist_ok=True)
    records = []
    sources = set()
    embedded = {}
    for component in components:
        assert component["group"] != "net.java.dev.stax-utils", component
        binary = runtime / component["file"]
        record = dict(component, sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
        with zipfile.ZipFile(binary) as jar:
            assert not any(name.startswith("javanet/staxutils/") for name in jar.namelist())
            for name in jar.namelist():
                if name.endswith("/"):
                    continue
                assert not name.startswith("/") and ".." not in pathlib.PurePosixPath(name).parts
                lower = name.lower()
                if name.endswith("pom.properties"):
                    values = dict(line.split("=", 1) for line in jar.read(name).decode("utf-8").splitlines()
                                  if "=" in line and not line.startswith("#"))
                    key = values["groupId"], values["artifactId"], values["version"]
                    embedded.setdefault(key, []).append(component["file"])
                if (any(token in lower for token in ("license", "notice", "copyright", "pom.xml", "pom.properties")) and not lower.endswith(".class")) or ("pdfa-" in lower and lower.endswith(".xml")):
                    path = destination / "embedded" / component["file"] / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(jar.read(name))
        key = component["group"], component["artifact"], component["version"]
        if key not in sources:
            sources.add(key)
            group, artifact, version = key
            base = "https://repo.maven.apache.org/maven2/" + group.replace(".", "/") + "/" + artifact + "/" + version + "/" + artifact + "-" + version
            record["sources"] = []
            for suffix in (".pom", "-sources.jar"):
                url = base + suffix
                with urllib.request.urlopen(url, timeout=30) as response:
                    data = response.read(32 * 1024 * 1024 + 1)
                assert len(data) <= 32 * 1024 * 1024
                path = destination / "sources" / group / artifact / version / (artifact + "-" + version + suffix)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                record["sources"].append({"url": url, "sha256": hashlib.sha256(data).hexdigest(), "path": str(path.relative_to(destination))})
        records.append(record)
        print(component["file"], flush=True)
    for group, artifact, version in sorted(set(embedded) - sources):
        record = {"group": group, "artifact": artifact, "version": version,
                  "embeddedIn": embedded[group, artifact, version], "sources": []}
        base = "https://repo.maven.apache.org/maven2/" + group.replace(".", "/") + "/" + artifact + "/" + version + "/" + artifact + "-" + version
        for suffix in (".pom", "-sources.jar"):
            url = base + suffix
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read(32 * 1024 * 1024 + 1)
            assert len(data) <= 32 * 1024 * 1024
            path = destination / "sources" / group / artifact / version / (artifact + "-" + version + suffix)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            record["sources"].append({"url": url, "sha256": hashlib.sha256(data).hexdigest(), "path": str(path.relative_to(destination))})
        records.append(record)
        print("embedded", group + ":" + artifact + ":" + version, flush=True)
    (destination / "inventory.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    # This is a retained source/notice inventory, not a completed recipient-access/distribution audit.


if __name__ == "__main__":
    main()
