import copy
import hashlib
import io
import json
import pathlib
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

from check_environment import check
from publish_candidate import check_loaded, validated, verify_public_libraries
from release_candidate import (APPLICATIONS, FINGERPRINT, assemble, require_checks, require_native,
                               runtime_changes, write)
from verify_libraries import MODULES


class ReleaseCandidateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name)
        self.version = "0.3.0-alpha.2"
        gate = {"version": self.version, "sourceCommit": "a" * 40, "sourceBaseline": "0.3.0-alpha.1"}
        write(self.root / "jvm/gate.json", gate)
        bundle = self.root / "jvm/central.zip"
        bundle.write_bytes(b"synthetic bundle identity fixture")
        signatures = [{"file": module + "-" + self.version + suffix, "sha256": "b" * 64}
                      for module in MODULES for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")]
        write(self.root / "jvm/central.zip.json", {"version": self.version, "fingerprint": FINGERPRINT,
              "sha256": hashlib.sha256(bundle.read_bytes()).hexdigest(), "signatures": signatures})
        for application, name in APPLICATIONS.items():
            for architecture in ("amd64", "arm64"):
                directory = self.root / (application + "-" + architecture)
                directory.mkdir()
                archive = directory / f"{name}-container-{self.version}-{architecture}.tar.gz"
                archive.write_bytes(b"synthetic archive identity fixture " + architecture.encode())
                write(directory / "archive.json", dict(gate, application=application, architecture=architecture,
                      file=archive.name, sha256=hashlib.sha256(archive.read_bytes()).hexdigest(), config="sha256:" + "c" * 64))

    def test_complete_candidate_and_no_overwrite(self):
        output = self.root / "candidate.json"
        result = assemble(self.root, output)
        self.assertEqual(24, len(result["libraryHashes"]))
        self.assertEqual(4, len(result["images"]))
        with self.assertRaises(FileExistsError):
            assemble(self.root, output)

    def test_modified_bundle_or_archive_is_rejected(self):
        for relative in ("jvm/central.zip", f"server-arm64/nuaforma-container-{self.version}-arm64.tar.gz"):
            path = self.root / relative
            original = path.read_bytes()
            path.write_bytes(b"altered")
            with self.subTest(relative=relative), self.assertRaises(ValueError):
                assemble(self.root, self.root / "candidate.json")
            path.write_bytes(original)

    def test_other_source_platform_version_or_baseline_rejected(self):
        path = self.root / "playground-arm64/archive.json"
        original = json.loads(path.read_text())
        for key, value in (("sourceCommit", "d" * 40), ("version", "0.3.0-alpha.1"),
                           ("architecture", "amd64"), ("application", "server"),
                           ("sourceBaseline", "0.2.0"), ("file", "../archive"), ("config", "latest")):
            path.write_text(json.dumps(dict(original, **{key: value})))
            with self.subTest(key=key), self.assertRaises(ValueError):
                assemble(self.root, self.root / "candidate.json")
        path.write_text(json.dumps(original))

    def test_missing_platform_or_duplicate_library_rejected(self):
        path = self.root / "server-arm64/archive.json"
        original = path.read_bytes()
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            assemble(self.root, self.root / "candidate.json")
        path.write_bytes(original)
        path = self.root / "jvm/central.zip.json"
        value = json.loads(path.read_text())
        value["signatures"][-1] = value["signatures"][0]
        path.write_text(json.dumps(value))
        with self.assertRaises(AssertionError):
            assemble(self.root, self.root / "candidate.json")

    def test_native_evidence_allows_only_version_and_release_changes(self):
        old = "version=0.3.0-alpha.1\norg.gradle.caching=true"
        new = old.replace("alpha.1", "alpha.2")
        self.assertEqual([], runtime_changes(["gradle.properties", "README.md", "tools/release/new.py",
                                               ".github/workflows/release.yml"], old, new))
        for path in ("nuaforma-pdf/src/main/File.kt", "nuaforma-docx-pdf/gradle.lockfile", "gradle/libs.versions.toml",
                     "tools/container/qualify_service.py", "deployment/pdf-runtime/Dockerfile", "build.gradle.kts"):
            self.assertEqual([path], runtime_changes([path], old, new))
        self.assertEqual(["gradle.properties"], runtime_changes(["gradle.properties"], old, new + "\nother=flag"))

    def test_real_sonar_and_both_java_checks_required(self):
        checks = [{"name": name, "app": {"slug": app}, "conclusion": "success"} for name, app in (
            ("Build (Java 17)", "github-actions"), ("Build (Java 25)", "github-actions"),
            ("SonarCloud Code Analysis", "sonarqubecloud"))]
        require_checks(checks)
        for index in range(3):
            bad = copy.deepcopy(checks)
            bad[index]["conclusion"] = "failure"
            with self.assertRaises(ValueError): require_checks(bad)
        with self.assertRaises(ValueError): require_checks(checks[:2])
        checks[-1]["app"]["slug"] = "github-actions"
        with self.assertRaises(ValueError): require_checks(checks)

    def test_native_run_requires_both_actual_architectures(self):
        run = {"path": ".github/workflows/native-container.yml", "conclusion": "success"}
        jobs = [{"name": f"Native container ({arch})", "conclusion": "success"} for arch in ("amd64", "arm64")]
        require_native(run, jobs)
        with self.assertRaises(ValueError): require_native(run, jobs[:1])
        with self.assertRaises(ValueError): require_native(dict(run, path="other.yml"), jobs)

    def test_single_environment_protection(self):
        environment = {"name": "release", "can_admins_bypass": False,
            "protection_rules": [{"type": "required_reviewers", "prevent_self_review": False,
                                  "reviewers": [{"type": "User", "reviewer": {"login": "mrhoeve"}}]}],
            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True}}
        policies = {"total_count": 1, "branch_policies": [{"type": "branch", "name": "main"}]}
        check(environment, policies, "release")
        with self.assertRaises(AssertionError): check(dict(environment, can_admins_bypass=True), policies, "release")

    def test_workflow_has_one_final_review_and_no_release_requalification(self):
        workflow = (pathlib.Path(__file__).resolve().parents[2] / ".github/workflows/release.yml").read_text()
        self.assertEqual(1, workflow.count("    environment:"))
        publish_job = workflow.split("\n  publish:\n", 1)[1]
        self.assertIn("    environment: release", publish_job)
        self.assertIn("    needs: review", publish_job)
        self.assertNotIn("./gradlew", publish_job)
        for forbidden in ("qualify_service.py", "qualify_endpoints.py", "verifyEmbeddedConsumers"):
            self.assertNotIn(forbidden, workflow)
        self.assertNotRegex(workflow, r"(?m)^.*\./gradlew[^\n]*\s(?:build|test)(?:\s|$)")


class PublicationTest(unittest.TestCase):
    @staticmethod
    def response(value):
        return io.BytesIO(json.dumps(value).encode())

    def test_validation_polls_reads_only_then_stops(self):
        states = iter(("VALIDATING", "VALIDATED"))
        calls = []
        def opener(request, timeout):
            calls.append(request.full_url)
            return self.response({"deploymentId": "identity", "deploymentState": next(states)})
        validated("identity", "synthetic token", opener=opener, sleep=lambda seconds: None)
        self.assertEqual(2, len(calls))
        self.assertTrue(all("/status?id=" in url for url in calls))

    def test_validation_failure_or_wrong_deployment_is_not_retried(self):
        for value in ({"deploymentId": "identity", "deploymentState": "FAILED"},
                      {"deploymentId": "other", "deploymentState": "VALIDATED"}):
            with self.assertRaises(ValueError):
                validated("identity", "synthetic token", opener=lambda *a, **k: self.response(value),
                          sleep=lambda seconds: self.fail("Unexpected retry"))

    def test_public_verification_retries_only_reads_and_rejects_changed_bytes(self):
        payload = b"public synthetic artifact"
        record = {"version": "0.3.0-alpha.2", "libraryHashes": {
            "nuaforma-core-0.3.0-alpha.2.jar": hashlib.sha256(payload).hexdigest()}}
        requests = []
        def opener(url, timeout):
            requests.append(url)
            if len(requests) == 1:
                raise urllib.error.HTTPError(url, 404, "propagating", {}, None)
            return io.BytesIO(payload)
        result = verify_public_libraries(record, opener=opener, sleep=lambda seconds: None)
        self.assertTrue(result["publicBytesMatch"])
        self.assertEqual(2, len(requests))
        self.assertTrue(requests[0].startswith("https://repo.maven.apache.org/maven2/dev/nuaforma/"))
        with self.assertRaises(ValueError):
            verify_public_libraries(record, opener=lambda *a, **k: io.BytesIO(b"altered"), sleep=lambda seconds: None)

    def test_loaded_image_checks_config_source_version_and_nonroot(self):
        image = {"config": "sha256:" + "c" * 64, "architecture": "arm64", "application": "server"}
        record = {"version": "0.3.0-alpha.2", "sourceCommit": "a" * 40}
        details = {"Id": image["config"], "Architecture": "arm64", "Os": "linux", "Config": {
            "User": "1000:1000", "Labels": {"org.opencontainers.image.version": record["version"],
            "org.opencontainers.image.revision": record["sourceCommit"], "dev.nuaforma.application": "server"}}}
        check_loaded(image, record, details)
        for key, value in (("Id", "sha256:" + "d" * 64), ("Architecture", "amd64"), ("Os", "windows")):
            with self.assertRaises(ValueError): check_loaded(image, record, dict(details, **{key: value}))
        details["Config"]["User"] = "root"
        with self.assertRaises(ValueError): check_loaded(image, record, details)


if __name__ == "__main__":
    unittest.main()
