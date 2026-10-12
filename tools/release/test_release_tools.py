import copy
import unittest
import pathlib
import tempfile
import zipfile
import struct
import hashlib

from check_environment import check
from verify_consumers import own_modules
from verify_libraries import MODULES, DEPENDENCIES, verify


class ReleaseProtectionTest(unittest.TestCase):
    def setUp(self):
        self.environment = {"name": "maven-central", "can_admins_bypass": False,
            "protection_rules": [{"type": "required_reviewers", "prevent_self_review": False,
                                  "reviewers": [{"type": "User", "reviewer": {"login": "mrhoeve"}}]}],
            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True}}
        self.policies = {"total_count": 1, "branch_policies": [{"type": "branch", "name": "main"}]}

    def test_expected_protection(self):
        check(self.environment, self.policies)

    def test_weaker_or_different_protection_is_rejected(self):
        for key, value in (("can_admins_bypass", True), ("name", "other"), ("protection_rules", []),
                           ("deployment_branch_policy", None)):
            with self.subTest(key=key), self.assertRaises(AssertionError):
                check(dict(self.environment, **{key: value}), self.policies)
        policies = dict(self.policies, branch_policies=[{"type": "branch", "name": "*"}])
        with self.assertRaises(AssertionError): check(self.environment, policies)
        environment = copy.deepcopy(self.environment)
        environment["protection_rules"][0]["reviewers"][0]["reviewer"]["login"] = "unexpected"
        with self.assertRaises(AssertionError): check(environment, self.policies)

    def test_longest_module_name_and_unknown_modules_are_not_hidden(self):
        self.assertEqual({"nuaforma-docx-pdf", "nuaforma-docx"}, own_modules(["nuaforma-docx-pdf-0.1.0.jar", "nuaforma-docx-0.1.0.jar"]))
        self.assertEqual({"unknown"}, own_modules(["nuaforma-server-0.1.0.jar"]))


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="nuaforma-publication-fixture-")
        self.addCleanup(self.directory.cleanup)
        self.repository = pathlib.Path(self.directory.name)
        for module in MODULES:
            folder = self.repository / "dev/nuaforma" / module / "0.1.0"
            folder.mkdir(parents=True)
            prefix = module + "-0.1.0"
            dependencies = "".join("<dependency><groupId>dev.nuaforma</groupId><artifactId>" + name +
                "</artifactId><version>0.1.0</version></dependency>" for name in DEPENDENCIES[module])
            (folder / (prefix + ".pom")).write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>dev.nuaforma</groupId><artifactId>' + module +
                '</artifactId><version>0.1.0</version><name>fixture</name><description>synthetic</description><url>https://example.invalid</url>' +
                '<licenses><license><name>Apache-2.0</name><url>https://example.invalid/license</url></license></licenses>' +
                '<developers><developer><name>synthetic</name></developer></developers><scm><url>https://example.invalid</url>' +
                '<connection>synthetic</connection><developerConnection>synthetic</developerConnection></scm><dependencies>' + dependencies + '</dependencies></project>', encoding="utf-8")
            for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
                with zipfile.ZipFile(folder / (prefix + suffix), "w") as archive:
                    archive.writestr("META-INF/LICENSE", "synthetic license fixture")
                    archive.writestr("META-INF/THIRD_PARTY.md", "synthetic notices fixture")
                    if suffix == "-sources.jar": archive.writestr("Source.kt", "synthetic")
                    elif suffix == "-javadoc.jar":
                        archive.writestr("README.md", "synthetic guide"); archive.writestr("ARCHITECTURE.md", "synthetic architecture")
                    else:
                        archive.writestr("Synthetic.class", b"\xca\xfe\xba\xbe\x00\x00" + struct.pack(">H", 61))
                        if module in ("nuaforma-docx", "nuaforma-docx-pdf"):
                            archive.writestr("META-INF/services/dev.nuaforma.contracts.DocumentProvider", "synthetic")
            for file in folder.iterdir():
                if file.suffix not in (".jar", ".pom"): continue
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    pathlib.Path(str(file) + "." + algorithm).write_text(hashlib.new(algorithm, file.read_bytes()).hexdigest(), encoding="ascii")

    def test_complete_fixture_passes(self):
        self.assertEqual(5, len(verify(self.repository, "0.1.0")))

    def test_bad_coordinates_missing_metadata_and_leaked_dependencies_fail(self):
        path = self.repository / "dev/nuaforma/nuaforma-contracts/0.1.0/nuaforma-contracts-0.1.0.pom"
        original = path.read_text()
        for changed in (original.replace("<groupId>dev.nuaforma</groupId>", "<groupId>wrong</groupId>", 1),
                        original.replace("<name>fixture</name>", ""),
                        original.replace("<dependencies>", "<dependencies><dependency><groupId>org.verapdf</groupId><artifactId>validation-model</artifactId></dependency>")):
            path.write_text(changed)
            with self.assertRaises(AssertionError): verify(self.repository, "0.1.0")
        path.write_text(original)

    def test_missing_sources_and_unsupported_class_version_fail(self):
        base = self.repository / "dev/nuaforma/nuaforma-contracts/0.1.0"
        binary = base / "nuaforma-contracts-0.1.0.jar"
        with zipfile.ZipFile(binary, "a") as archive:
            archive.writestr("TooNew.class", b"\xca\xfe\xba\xbe\x00\x00" + struct.pack(">H", 65))
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            pathlib.Path(str(binary) + "." + algorithm).write_text(hashlib.new(algorithm, binary.read_bytes()).hexdigest(), encoding="ascii")
        with self.assertRaises(AssertionError): verify(self.repository, "0.1.0")
        (base / "nuaforma-contracts-0.1.0-sources.jar").unlink()
        with self.assertRaises(AssertionError): verify(self.repository, "0.1.0")

    def test_incorrect_checksum_fails(self):
        path = self.repository / "dev/nuaforma/nuaforma-contracts/0.1.0/nuaforma-contracts-0.1.0.jar.sha256"
        path.write_text("0" * 64)
        with self.assertRaises(AssertionError): verify(self.repository, "0.1.0")


if __name__ == "__main__": unittest.main()
