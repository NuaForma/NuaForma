import copy
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import ci_evidence as evidence


class QualificationTest(unittest.TestCase):
    def setUp(self):
        self.fingerprint = "f" * 64
        self.run = {"id": 42, "head_sha": "a" * 40, "run_attempt": 2,
                    "repository": {"full_name": evidence.REPOSITORY},
                    "path": ".github/workflows/build.yml", "event": "pull_request", "conclusion": "success"}
        self.jobs = [{"name": f"Build (Java {lane})", "status": "completed", "conclusion": "success"}
                     for lane in ("17", "25")]
        self.receipts = {lane: {"schema": 1, "full": True, "repository": evidence.REPOSITORY,
                               "run": 42, "attempt": 2, "sourceCommit": "a" * 40,
                               "testedCommit": "b" * 40, "kind": "java", "lane": lane,
                               "fingerprint": self.fingerprint} for lane in ("17", "25")}
        self.check = {"id": 12, "head_sha": "a" * 40, "name": "SonarCloud Code Analysis",
                      "app": {"slug": "sonarqubecloud"}, "status": "completed", "conclusion": "success"}

    def test_both_successful_lanes_exact_attempt_and_input_are_required(self):
        evidence.validate_run(self.run, self.jobs, "java")
        evidence.validate_receipts(self.run, self.receipts, "java", self.fingerprint)
        for field, value in (("run", 43), ("attempt", 1), ("sourceCommit", "c" * 40),
                             ("testedCommit", "invalid"), ("full", False), ("lane", "25"),
                             ("repository", "other/fork"), ("fingerprint", "e" * 64)):
            bad = copy.deepcopy(self.receipts)
            bad["17"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                evidence.validate_receipts(self.run, bad, "java", self.fingerprint)
        with self.assertRaises(ValueError):
            evidence.validate_receipts(self.run, {"17": self.receipts["17"]}, "java", self.fingerprint)
        for change in ({"path": "other.yml"}, {"repository": {"full_name": "other/fork"}}):
            with self.assertRaises(ValueError):
                evidence.validate_run(dict(self.run, **change), self.jobs, "java")
        for conclusion in ("failure", "skipped", "cancelled", None):
            with self.assertRaises(ValueError):
                evidence.validate_run(self.run, [dict(j, conclusion=conclusion) for j in self.jobs], "java")

    def api(self, path):
        if path.startswith("actions/artifacts?"):
            return {"artifacts": [{"id": 10, "workflow_run": {"id": 42}}]}
        if path == "actions/runs/42":
            return self.run
        if "/artifacts?" in path:
            return {"artifacts": [{"name": f"qualification-java-{lane}-{self.fingerprint}", "lane": lane} for lane in self.receipts]}
        if "/jobs?" in path:
            if "/attempts/" in path and hasattr(self, "original_jobs"):
                return {"total_count": len(self.original_jobs), "jobs": self.original_jobs}
            return {"total_count": len(self.jobs), "jobs": self.jobs}
        if "/check-runs?" in path:
            return {"total_count": 1, "check_runs": [self.check]}
        self.fail("Unexpected network API: " + path)

    def qualify(self, producer=None, ancestor=0):
        with patch.object(evidence, "api", side_effect=self.api), \
                patch.object(evidence, "input_fingerprint", return_value=self.fingerprint), \
                patch.object(evidence, "command", return_value=b"trusted producer blob", side_effect=producer), \
                patch.object(evidence, "read_receipt", side_effect=lambda a: self.receipts[a["lane"]]), \
                patch.object(evidence.subprocess, "run", return_value=subprocess.CompletedProcess([], ancestor)):
            return evidence.qualification("java")

    def test_matching_full_merge_qualification_retains_actual_sonar_revision(self):
        result = self.qualify()
        self.assertEqual(42, result["run"])
        self.assertEqual("a" * 40, result["sourceCommit"])
        self.assertEqual("b" * 40, result["testedCommit"])

    def test_changed_inputs_or_missing_evidence_request_full_checks(self):
        self.receipts["17"]["fingerprint"] = "c" * 64
        self.assertIsNone(self.qualify())
        del self.receipts["17"]
        self.assertIsNone(self.qualify())

    def test_scanner_upload_pending_fake_and_failed_sonar_cannot_qualify(self):
        self.check["status"] = "in_progress"
        self.assertIsNone(self.qualify())
        self.check.update(status="completed", conclusion="failure")
        with self.assertRaises(ValueError):
            self.qualify()
        self.check.update(conclusion="success", app={"slug": "github-actions"})
        self.assertIsNone(self.qualify())

    def test_receipts_from_a_reused_wrapper_are_never_full_qualification_roots(self):
        self.receipts["17"]["full"] = False
        with self.assertRaises(ValueError):
            self.qualify()

    def test_different_producer_or_unmerged_source_cannot_authorize_reuse(self):
        with self.assertRaisesRegex(ValueError, "producer differs"):
            self.qualify(producer=[b"untrusted producer", b"current producer"])
        with self.assertRaisesRegex(ValueError, "selected history"):
            self.qualify(ancestor=1)

    def test_retrying_only_a_failed_lane_or_publication_preserves_other_successful_test_evidence(self):
        self.receipts["17"]["attempt"] = 1
        self.original_jobs = copy.deepcopy(self.jobs)
        self.jobs[0]["run_attempt"] = 2
        self.jobs[1]["run_attempt"] = 2
        self.assertEqual({"17": 1, "25": 2}, self.qualify()["laneAttempts"])
        self.original_jobs[0]["conclusion"] = "failure"
        with self.assertRaises(ValueError):
            self.qualify()


class FingerprintTest(unittest.TestCase):
    def test_real_git_trees_reuse_version_and_documentation_but_detect_source_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            subprocess.run(["git", "init", "--quiet", folder], check=True)
            def put(name, content):
                blob = subprocess.check_output(["git", "hash-object", "-w", "--stdin"], cwd=root, input=content).decode().strip()
                subprocess.run(["git", "update-index", "--add", "--cacheinfo", "100644," + blob + "," + name], cwd=root, check=True)
            def tree():
                return subprocess.check_output(["git", "write-tree"], cwd=root).decode().strip()
            put("gradle.properties", b"version=1.2.3-alpha.1\nflag=true\n")
            put("README.md", b"Original documentation")
            put("nuaforma-core/src/main/File.kt", b"original source")
            before = tree()
            put("gradle.properties", b"version=1.2.3-alpha.2\nflag=true\n")
            put("README.md", b"Updated documentation")
            version_only = tree()
            put("nuaforma-core/src/main/File.kt", b"changed source")
            changed = tree()
            with patch.object(evidence, "ROOT", root):
                for kind in ("java", "native"):
                    self.assertEqual(evidence.input_fingerprint(kind, before), evidence.input_fingerprint(kind, version_only))
                    self.assertNotEqual(evidence.input_fingerprint(kind, before), evidence.input_fingerprint(kind, changed))

    def fingerprint(self, version=b"version=1.2.3-alpha.1\nflag=true\n", source="a", docs="b", mode=b"100644"):
        listing = (b"100644 blob version\tgradle.properties\0" + mode + b" blob " + source.encode()
                   + b"\tnuaforma-core/src/main/File.kt\0" + b"100644 blob " + docs.encode() + b"\tREADME.md\0")
        def git(*args):
            return listing if args[1] == "ls-tree" else version
        return {kind: evidence.input_fingerprint(kind, git=git) for kind in ("java", "native")}

    def test_release_version_only_preserves_both_input_identities(self):
        self.assertEqual(self.fingerprint(), self.fingerprint(version=b"version=1.2.3-alpha.2\nflag=true\n"))

    def test_code_toolchain_flags_and_executable_modes_invalidate_reuse(self):
        original = self.fingerprint()
        for changed in (self.fingerprint(source="changed"), self.fingerprint(mode=b"100755"),
                        self.fingerprint(version=b"version=1.2.3-alpha.1\nflag=false\n")):
            for kind in original:
                self.assertNotEqual(original[kind], changed[kind])
        self.assertEqual(original["java"], self.fingerprint(docs="changed")["java"])
        self.assertEqual(original["native"], self.fingerprint(docs="changed")["native"])
        for path in ("gradle/libs.versions.toml", "deployment/pdf-runtime/Dockerfile",
                     "tools/container/qualify_service.py", ".github/workflows/native-container.yml"):
            self.assertTrue(evidence.native_input(path))
