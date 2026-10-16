"""Exercise Gradle's real fail-closed verifier with a private synthetic Maven fixture."""

import hashlib
from pathlib import Path
import subprocess
import tempfile
import zipfile

from dependency_verification import ROOT


def main():
    with tempfile.TemporaryDirectory(prefix="nuaforma-verification-") as directory:
        root = Path(directory)
        repository = root / "repository/example/fixture/1.0"
        repository.mkdir(parents=True)
        jar = repository / "fixture-1.0.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("fixture.txt", "Synthetic verification fixture, not executable code")
        pom = repository / "fixture-1.0.pom"
        pom.write_text('<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>'
                       '<artifactId>fixture</artifactId><version>1.0</version></project>')
        (root / "settings.gradle").write_text("rootProject.name = 'verification-fixture'\n")
        (root / "build.gradle").write_text(
            "repositories { maven { url = uri('repository') } }\n"
            "configurations { fixture }\n"
            "dependencies { fixture 'example:fixture:1.0' }\n"
            "tasks.register('resolve') { doLast { configurations.fixture.files.each { assert it.isFile() } } }\n")
        metadata = root / "gradle/verification-metadata.xml"
        metadata.parent.mkdir()
        header = ('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
                  '<configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures>'
                  '</configuration><components><component group="example" name="fixture" version="1.0">')
        entries = ''.join(f'<artifact name="{file.name}"><sha256 value="{hashlib.sha256(file.read_bytes()).hexdigest()}"/></artifact>'
                          for file in (jar, pom))
        tail = '</component></components></verification-metadata>'
        valid = header + entries + tail
        original = jar.read_bytes()
        for case, content, success in (
                ("reviewed", valid, True),
                ("modified", valid, False),
                ("unknown", header + tail, False)):
            jar.write_bytes(original + b"tampered" if case == "modified" else original)
            metadata.write_text(content)
            result = subprocess.run(["bash", str(ROOT / "gradlew"), "--no-daemon", "--offline", "-p", str(root),
                                     "--dependency-verification=strict", "resolve"], cwd=ROOT,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)
            if (result.returncode == 0) != success or (not success and "Dependency verification failed" not in result.stdout):
                raise RuntimeError(f"Gradle verification probe failed ({case}):\n{result.stdout}")
            print(f"PASS: Gradle {case} artifact {'accepted' if success else 'rejected'}")


if __name__ == "__main__":
    main()
