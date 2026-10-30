"""Compile/run independent consumers using staged Maven artifacts, never checkout product sources."""
import argparse
import json
import hashlib
import os
import pathlib
import re
import subprocess
import zipfile
from verify_libraries import SOURCE_MODULES as MODULES, artifacts, project_version


def own_modules(files):
    return {next((module for module in sorted(MODULES, key=len, reverse=True) if pathlib.Path(file).name.startswith(module + "-")), "unknown")
            for file in files if pathlib.Path(file).name.startswith("nuaforma-")}


def run(command, root, log):
    result = subprocess.run(command, cwd=root, capture_output=True, text=True, timeout=600)
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(result.stdout + result.stderr, encoding="utf-8")
    assert result.returncode == 0, "Consumer check failed; inspect " + str(log)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--version", default=project_version())
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    java = subprocess.run(["java", "-XshowSettings:properties", "-version"], capture_output=True, text=True, timeout=30)
    (output / "java-runtime.log").write_text(java.stdout + java.stderr, encoding="utf-8")
    assert java.returncode == 0 and re.search(r"java.specification.version\s*=\s*17\b", java.stderr), "Independent library consumers must actually run on Java 17"
    repository = args.repository.resolve()
    reviewed = json.loads((root / "build/library-runtime-files.json").read_text(encoding="utf-8"))
    def verify_runtime(files, modules):
        expected = {pathlib.Path(file).name: hashlib.sha256(pathlib.Path(file).read_bytes()).hexdigest()
                    for module in modules for file in reviewed[module]}
        actual = {pathlib.Path(file).name: hashlib.sha256(pathlib.Path(file).read_bytes()).hexdigest()
                  for file in files if not pathlib.Path(file).name.startswith(("nuaforma-", "provider-example-"))}
        if actual != expected:
            (output / "runtime-difference.json").write_text(json.dumps({"modules": sorted(modules),
                "expected": expected, "actual": actual}, indent=2), encoding="utf-8")
        assert actual == expected, "Independent consumer runtime differs from the reviewed Gradle artifact graph"
    candidate_digest = hashlib.sha256()
    for module in MODULES:
        for file in artifacts(repository, module, args.version):
            candidate_digest.update(hashlib.sha256(file.read_bytes()).digest())
    consumer_repository = output / "m2" / args.version / candidate_digest.hexdigest()
    run(["mvn", "-B", "-U", "-ntp", "-f", "examples/embedded/maven/pom.xml", "-Dmaven.repo.local=" + str(consumer_repository),
         "-Dnuaforma.repository=" + repository.as_uri(), "-Dnuaforma.version=" + args.version,
         "clean", "install", "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies", "-DincludeScope=runtime", "-Dmdep.overWriteSnapshots=true", "-Dmdep.overWriteReleases=true", "-DskipTests"],
        root, output / "maven.log")
    records = []
    for module, entrypoint, own in (
        ("core", "CoreExample", {"nuaforma-contracts", "nuaforma-core"}),
        ("docx", "DocxExample", {"nuaforma-contracts", "nuaforma-core", "nuaforma-template", "nuaforma-docx"}),
        ("pdf", "PdfExample", {"nuaforma-contracts", "nuaforma-core", "nuaforma-template", "nuaforma-docx", "nuaforma-docx-pdf", "nuaforma-pdf"}),
        ("pdf-engine", "PdfEngineExample", {"nuaforma-pdf"}),
    ):
        target = root / "examples/embedded/maven" / module / "target"
        files = sorted((target / "dependency" / args.version).glob("*.jar"))
        names = own_modules(files)
        assert names == own, (module, names)
        verify_runtime(files, own)
        for name in names:
            resolved = next(file for file in files if own_modules([file]) == {name})
            assert hashlib.sha256(resolved.read_bytes()).digest() == hashlib.sha256(artifacts(repository, name, args.version)[1].read_bytes()).digest()
        assert not any(file.name.startswith(("ktor-", "junit-", "mockk", "archunit", "validation-model", "verapdf")) for file in files)
        if module in ("core", "docx"):
            assert not any(re.match(r"pdfbox-\d", file.name) for file in files)
        if module == "core":
            assert not any(file.name.startswith(("docx4j-", "jaxb-")) for file in files)
        classpath = os.pathsep.join([str(target / "classes")] + [str(file) for file in files])
        command = ["java", "-cp", classpath, "dev.nuaforma.examples." + entrypoint]
        if module == "docx": command.append(str(output / "maven.docx"))
        if module == "pdf-engine":
            assert not any(file.name.startswith(("docx4j-", "jaxb-", "nuaforma-server-")) for file in files)
            command.extend([str(root / "nuaforma-pdf/src/test/resources/fonts/LiberationSans-Regular.ttf"),
                            str(output / "independent-engine.pdf")])
        message = run(command, root, output / (module + ".log"))
        records.append({"consumer": "maven-" + module, "passed": True, "javaFeature": 17, "modules": sorted(names), "result": message.strip()})
    provider_files = list((root / "examples/embedded/maven/provider/target/dependency" / args.version).glob("*.jar"))
    assert own_modules(provider_files) == {"nuaforma-contracts"}
    run([str(root / "gradlew"), "--no-daemon", "--project-dir", "examples/embedded/gradle", "--refresh-dependencies",
         "-PnuaformaRepository=" + repository.as_uri(), "-PnuaformaVersion=" + args.version, "run", "writeRuntimeInventory", "--console=plain"],
        root, output / "gradle.log")
    files = (root / "examples/embedded/gradle/build/runtime-files.txt").read_text(encoding="utf-8").splitlines()
    assert not any(pathlib.Path(file).name.startswith(("nuaforma-docx-pdf-", "nuaforma-server-", "ktor-", "junit-", "validation-model"))
                   or re.match(r"pdfbox-\d", pathlib.Path(file).name) for file in files)
    names = own_modules(files)
    assert names == {"nuaforma-contracts", "nuaforma-core", "nuaforma-template", "nuaforma-docx"}, names
    verify_runtime(files, names)
    for name in names:
        resolved = next(pathlib.Path(file) for file in files if own_modules([file]) == {name})
        assert hashlib.sha256(resolved.read_bytes()).digest() == hashlib.sha256(artifacts(repository, name, args.version)[1].read_bytes()).digest()
    records.append({"consumer": "gradle-kotlin-docx", "passed": True, "modules": sorted(names)})
    (output / "verification.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    print(f"{len(records)} independent Java/Kotlin consumer checks passed; configured PDF conversion remains a separate runtime check")


if __name__ == "__main__":
    main()
