"""Future publication refuses incomplete, emulated or mismatched platform review."""
import copy
import hashlib
import io
import json
import pathlib
import tarfile
import tempfile
import unittest
from validate_multiarch_release import check
from check_multiarch_archive import validate_archive
from check_multiarch_index import check as check_index


def reviewed():
    version, commit = "0.1.0-alpha.2", "a" * 40
    platform = {"approved": {name: True for name in ("containerAudit", "sourceAccess", "fidelityQualification", "securityReview")},
                "containerArchiveSha256": "b" * 64, "containerConfigSha256": "c" * 64,
                "sourceMaterials": [{"url": f"https://github.com/NuaForma/NuaForma/releases/download/v{version}/sources.tar", "sha256": "d" * 64, "verifiedAnonymousAccess": True}]}
    record = {"version": version, "sourceCommit": commit, "reviewer": "mrhoeve", "latest": False, "platforms": {}}
    for architecture in ("amd64", "arm64"):
        record["platforms"][architecture] = dict(copy.deepcopy(platform), nativeQualificationArchitecture=architecture)
    return record


class MultiarchReleaseTest(unittest.TestCase):
    def test_application_identity_is_not_interchangeable(self):
        record = reviewed()
        record["application"] = "unknown"
        with self.assertRaises(ValueError):
            check(record, record["version"], record["sourceCommit"])
        record["application"] = "playground"
        check(record, record["version"], record["sourceCommit"])

    def test_complete_record(self):
        record = reviewed()
        check(record, record["version"], record["sourceCommit"])

    def test_incomplete_or_invalid_gate(self):
        variants = []
        record = reviewed(); del record["platforms"]["arm64"]; variants.append(record)
        record = reviewed(); record["platforms"]["arm64"]["approved"]["fidelityQualification"] = False; variants.append(record)
        record = reviewed(); record["platforms"]["arm64"]["nativeQualificationArchitecture"] = "amd64"; variants.append(record)
        record = reviewed(); record["latest"] = True; variants.append(record)
        record = reviewed(); record["version"] = "0.1.0-alpha.1"; variants.append(record)
        record = reviewed(); record["platforms"]["amd64"]["sourceMaterials"] = []; variants.append(record)
        record = reviewed(); record["platforms"]["arm64"]["sourceMaterials"][0]["url"] = "https://example.invalid/sources.tar"; variants.append(record)
        record = reviewed(); record["sourceCommit"] = "invalid"; variants.append(record)
        for record in variants:
            with self.subTest(record=record), self.assertRaises(ValueError):
                check(record, record["version"], record["sourceCommit"])

    def test_archive_identity_and_platform(self):
        for architecture in ("amd64", "arm64"):
            record = reviewed()
            config = {"os": "linux", "architecture": architecture, "rootfs": {"diff_ids": ["sha256:" + "f" * 64]},
                      "config": {"User": "1000:1000", "Labels": {"org.opencontainers.image.version": record["version"], "org.opencontainers.image.revision": record["sourceCommit"]}}}
            data = json.dumps(config).encode()
            with tempfile.TemporaryDirectory() as directory:
                path = pathlib.Path(directory) / f'nuaforma-container-{record["version"]}-{architecture}.tar.gz'
                with tarfile.open(path, "w:gz") as archive:
                    member = tarfile.TarInfo("config.json"); member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
                platform = record["platforms"][architecture]
                platform["containerConfigSha256"] = hashlib.sha256(data).hexdigest()
                platform["containerArchiveSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
                self.assertEqual(config, validate_archive(path, record, architecture))
                platform["containerConfigSha256"] = "e" * 64
                with self.assertRaises(ValueError):
                    validate_archive(path, record, architecture)

    def test_playground_archive_requires_its_own_identity_and_defaults(self):
        for architecture in ("amd64", "arm64"):
            record = reviewed()
            record["application"] = "playground"
            config = {"os": "linux", "architecture": architecture,
                      "config": {"User": "1000:1000", "Labels": {
                          "dev.nuaforma.application": "playground",
                          "org.opencontainers.image.version": record["version"],
                          "org.opencontainers.image.revision": record["sourceCommit"]},
                          "Env": ["NUAFORMA_APPLICATION=playground", "NUAFORMA_PLAYGROUND=true", "NUAFORMA_MODE=development"]}}
            with tempfile.TemporaryDirectory() as directory:
                path = pathlib.Path(directory) / f'nuaforma-playground-container-{record["version"]}-{architecture}.tar.gz'

                def write(candidate):
                    data = json.dumps(candidate).encode()
                    with tarfile.open(path, "w:gz") as archive:
                        member = tarfile.TarInfo("config.json")
                        member.size = len(data)
                        archive.addfile(member, io.BytesIO(data))
                    record["platforms"][architecture].update(
                        containerConfigSha256=hashlib.sha256(data).hexdigest(),
                        containerArchiveSha256=hashlib.sha256(path.read_bytes()).hexdigest())

                write(config)
                self.assertEqual(config, validate_archive(path, record, architecture))
                candidate = copy.deepcopy(config)
                candidate["config"]["Env"] = []
                write(candidate)
                with self.assertRaises(ValueError):
                    validate_archive(path, record, architecture)
                candidate = copy.deepcopy(config)
                candidate["config"]["Labels"]["dev.nuaforma.application"] = "server"
                write(candidate)
                with self.assertRaises(ValueError):
                    validate_archive(path, record, architecture)
                write(config)
                renamed = path.with_name(path.name.replace("nuaforma-playground-", "nuaforma-"))
                path.rename(renamed)
                with self.assertRaises(ValueError):
                    validate_archive(renamed, record, architecture)

    def test_index_requires_complete_platform_set(self):
        index = {"schemaVersion": 2, "manifests": [{"platform": {"os": "linux", "architecture": arch}, "digest": "sha256:" + value * 64} for arch, value in (("amd64", "1"), ("arm64", "2"))]}
        check_index(index)
        for broken in (dict(index, manifests=index["manifests"][:1]), dict(index, manifests=index["manifests"][:1] * 2), dict(index, schemaVersion=1)):
            with self.assertRaises(ValueError):
                check_index(broken)

    def test_index_binds_child_configs_and_manifest_digests(self):
        index = {"schemaVersion": 2, "manifests": [{"platform": {"os": "linux", "architecture": arch}, "digest": "sha256:" + value * 64} for arch, value in (("amd64", "1"), ("arm64", "2"))]}
        descriptors = {arch: {"Descriptor": {"digest": "sha256:" + value * 64}, "OCIManifest": {"config": {"digest": "sha256:" + "c" * 64}}} for arch, value in (("amd64", "1"), ("arm64", "2"))}
        hashes = {arch: "c" * 64 for arch in ("amd64", "arm64")}
        check_index(index, descriptors, hashes)
        changed = copy.deepcopy(descriptors)
        changed["arm64"]["Descriptor"]["digest"] = "sha256:" + "3" * 64
        with self.assertRaises(ValueError):
            check_index(index, changed, hashes)
        changed = copy.deepcopy(descriptors)
        changed["arm64"]["OCIManifest"]["config"]["digest"] = "sha256:" + "d" * 64
        with self.assertRaises(ValueError):
            check_index(index, changed, hashes)


if __name__ == "__main__":
    unittest.main()
