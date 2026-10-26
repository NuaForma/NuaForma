"""Verify the standalone local publications; never upload or load credentials."""
import argparse
import hashlib
import json
import pathlib
import re
import struct
import xml.etree.ElementTree as ET
import zipfile

if not __debug__:
    raise RuntimeError("Release verification must run without Python optimization")

MODULES = ("nuaforma-contracts", "nuaforma-core", "nuaforma-template", "nuaforma-docx", "nuaforma-pdf", "nuaforma-docx-pdf")
SOURCE_MODULES = MODULES
DEPENDENCIES = {
    "nuaforma-contracts": set(), "nuaforma-core": {"nuaforma-contracts"},
    "nuaforma-template": {"nuaforma-contracts"}, "nuaforma-docx": {"nuaforma-contracts", "nuaforma-template"},
    "nuaforma-docx-pdf": {"nuaforma-contracts", "nuaforma-docx", "nuaforma-template", "nuaforma-pdf"},
    "nuaforma-pdf": set(),
}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def project_version():
    root = pathlib.Path(__file__).resolve().parents[2]
    versions = [line.removeprefix("version=").strip() for line in (root / "gradle.properties").read_text(encoding="utf-8").splitlines()
                if line.startswith("version=")]
    assert len(versions) == 1 and re.fullmatch(r"[A-Za-z0-9_.+-]+", versions[0])
    return versions[0]


def artifacts(repository, module, version):
    folder = repository / "dev/nuaforma" / module / version
    selections = [("pom", ""), ("jar", ""), ("jar", "sources"), ("jar", "javadoc")]
    snapshots = ET.parse(folder / "maven-metadata.xml").getroot() if version.endswith("-SNAPSHOT") else None
    files = []
    for extension, classifier in selections:
        resolved = version
        if snapshots is not None:
            matches = [item.findtext("value") for item in snapshots.findall("versioning/snapshotVersions/snapshotVersion")
                       if item.findtext("extension") == extension and (item.findtext("classifier") or "") == classifier]
            assert len(matches) == 1, (module, extension, classifier)
            resolved = matches[0]
        assert re.fullmatch(r"[A-Za-z0-9_.+-]+", resolved)
        files.append(folder / (module + "-" + resolved + ("-" + classifier if classifier else "") + "." + extension))
    return files


def verify(repository, version, modules=MODULES):
    records = []
    for module in modules:
        files = artifacts(repository, module, version)
        pom = ET.parse(files[0]).getroot()
        def value(path):
            return pom.findtext(path, namespaces=NS)
        assert value("m:groupId") == "dev.nuaforma"
        assert value("m:artifactId") == module and value("m:version") == version
        for path in ("m:name", "m:description", "m:url", "m:licenses/m:license/m:name",
                     "m:licenses/m:license/m:url", "m:developers/m:developer/m:name",
                     "m:scm/m:url", "m:scm/m:connection", "m:scm/m:developerConnection"):
            assert value(path), (module, path)
        own = set()
        for dependency in pom.findall("m:dependencies/m:dependency", NS):
            group = dependency.findtext("m:groupId", namespaces=NS)
            name = dependency.findtext("m:artifactId", namespaces=NS)
            assert not group.startswith(("org.junit", "io.mockk", "com.tngtech.archunit", "io.swagger", "org.verapdf", "io.ktor")), (module, group)
            assert name != "nuaforma-server"
            if module not in ("nuaforma-docx", "nuaforma-docx-pdf"):
                assert not group.startswith(("org.docx4j", "jakarta.xml.bind")), (module, group)
                if module != "nuaforma-pdf":
                    assert not group.startswith("org.apache.pdfbox"), (module, group)
            if group == "dev.nuaforma":
                own.add(name)
                assert dependency.findtext("m:version", namespaces=NS) == version
        assert own == DEPENDENCIES[module], (module, own)
        for file in files:
            assert file.is_file() and file.stat().st_size > 0, file
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = pathlib.Path(str(file) + "." + algorithm)
                assert checksum.is_file(), checksum
                assert checksum.read_text(encoding="ascii").strip().lower() == hashlib.new(algorithm, file.read_bytes()).hexdigest(), checksum
        for file in files[1:]:
            with zipfile.ZipFile(file) as archive:
                assert "META-INF/LICENSE" in archive.namelist() and "META-INF/THIRD_PARTY.md" in archive.namelist()
                if file.name.endswith("-sources.jar"):
                    assert any(name.endswith(".kt") for name in archive.namelist())
                elif file.name.endswith("-javadoc.jar"):
                    assert "README.md" in archive.namelist() and "ARCHITECTURE.md" in archive.namelist()
                else:
                    names = archive.namelist()
                    assert not any(name.startswith(("io/ktor/", "org/verapdf/", "org/junit/", "org/docx4j/")) for name in names)
                    for name in names:
                        if name.endswith(".class"):
                            data = archive.read(name)
                            assert data[:4] == b"\xca\xfe\xba\xbe" and struct.unpack(">H", data[6:8])[0] <= 61, name
                    if module == "nuaforma-docx-pdf":
                        assert not any("/adapters/libreoffice/" in name or name.endswith(("/LibreOfficeConfiguration.class", "/LegacyDocxPdfProvider.class")) for name in names), "Retired renderer code leaked into native production artifact"
                    if module in ("nuaforma-docx", "nuaforma-docx-pdf"):
                        assert "META-INF/services/dev.nuaforma.contracts.DocumentProvider" in names
        records.append({"module": module, "version": version, "files": [
            {"name": file.name, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()} for file in files]})
    return records


def verify_java17_classes(file):
    """Inspect the effective Java 17 class view, not optional newer multi-release implementations."""
    with zipfile.ZipFile(file) as archive:
        names = set(archive.namelist())
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8") if "META-INF/MANIFEST.MF" in names else ""
        multi = re.search(r"^Multi-Release:\s*true\s*$", manifest, re.MULTILINE | re.IGNORECASE) is not None
        selected = {}
        for name in names:
            if not name.endswith(".class"):
                continue
            if name.startswith("META-INF/versions/"):
                match = re.fullmatch(r"META-INF/versions/(\d+)/(.+\.class)", name)
                if not multi or match is None or not 9 <= int(match[1]) <= 17:
                    continue
                version, key = int(match[1]), match[2]
            else:
                version, key = 0, name
            if key not in selected or selected[key][0] < version:
                selected[key] = (version, name)
        for _, name in selected.values():
            data = archive.read(name)
            assert len(data) >= 8 and data[:4] == b"\xca\xfe\xba\xbe", (file.name, name)
            minor, major = struct.unpack(">HH", data[4:8])
            assert major <= 61 and minor != 65535, ("Java 17 runtime dependency violation", file.name, name, major, minor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=pathlib.Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--include-experimental-pdf-engine", action="store_true",
                        help="Check the source-only engine locally; does not change release bundle membership")
    args = parser.parse_args()
    inventory = pathlib.Path(__file__).resolve().parents[2] / "build/library-runtime-files.json"
    runtime = json.loads(inventory.read_text(encoding="utf-8"))
    modules = SOURCE_MODULES if args.include_experimental_pdf_engine else MODULES
    assert set(runtime) == set(modules), "All selected library runtime graphs must be checked"
    for file in sorted({path for paths in runtime.values() for path in paths}):
        verify_java17_classes(pathlib.Path(file))
    records = verify(args.repository, args.version, modules)
    print(json.dumps(records, indent=2))


if __name__ == "__main__":
    main()
