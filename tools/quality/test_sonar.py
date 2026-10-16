import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

import check_coverage
from sonar_event import eligible


class SonarEventTest(unittest.TestCase):
    def check_event(self, name="pull_request", ref="refs/pull/1/merge", repo="NuaForma/NuaForma",
                    actor="maintainer", head="NuaForma/NuaForma", base="develop", author="maintainer",
                    action="opened"):
        event = {"action": action, "pull_request": {
            "head": {"repo": {"full_name": head}},
            "base": {"repo": {"full_name": "NuaForma/NuaForma"}, "ref": base},
            "user": {"login": author},
        }}
        return eligible(name, ref, repo, actor, event)

    def test_trusted_prs(self):
        for base in ("main", "develop"):
            for action in ("opened", "synchronize", "reopened"):
                self.assertTrue(self.check_event(base=base, action=action))

    def test_branches_and_dispatch(self):
        for name in ("push", "workflow_dispatch"):
            for branch in ("main", "develop"):
                self.assertTrue(self.check_event(name=name, ref=f"refs/heads/{branch}"))
            self.assertFalse(self.check_event(name=name, ref="refs/heads/codex/test"))
            self.assertFalse(self.check_event(name=name, ref="refs/tags/v1"))

    def test_untrusted_events(self):
        for values in ({"head": "attacker/fork"}, {"head": None}, {"base": "other"},
                       {"actor": "dependabot[bot]"}, {"author": "dependabot[bot]"},
                       {"repo": "other/fork"}, {"name": "pull_request_target"},
                       {"name": "workflow_run"}, {"action": "closed"}):
            with self.subTest(values=values):
                self.assertFalse(self.check_event(**values))
        self.assertFalse(eligible("pull_request", "", "NuaForma/NuaForma", "maintainer", {}))

    def test_workflow_secret_boundary(self):
        workflow = (check_coverage.ROOT / ".github/workflows/build.yml").read_text()
        self.assertEqual(workflow.count("secrets.SONAR_TOKEN"), 1)
        scan = workflow.split("- name: Analyze with SonarQube Cloud\n", 1)[1]
        self.assertIn("if: matrix.java == '25' && steps.sonar.outputs.analyze == 'true'", scan)
        self.assertIn('if [ -z "$SONAR_TOKEN" ]; then', scan)
        self.assertIn("exit 1", scan)
        self.assertIn("-x test -x coverageReport", scan)
        self.assertNotIn("pull_request_target", workflow)
        self.assertNotIn("continue-on-error", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("fetch-depth: 0", workflow)


class CoverageInventoryTest(unittest.TestCase):
    def test_inventory_rejects_missing_data_and_test_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = root / "module/build/classes/kotlin/main/example"
            main.mkdir(parents=True)
            (main / "Production.class").touch()
            test = root / "module/build/classes/kotlin/test/example"
            test.mkdir(parents=True)
            (test / "Test.class").touch()
            report = root / check_coverage.REPORT
            report.parent.mkdir(parents=True)
            report.write_text('<report><package><class name="example/Production"/></package>'
                              '<counter type="LINE" covered="1" missed="2"/></report>')
            with patch.object(check_coverage, "MODULES", ("module",)):
                with self.assertRaisesRegex(ValueError, "execution data"):
                    check_coverage.validate(root)
                execution = root / "module/build/jacoco/test.exec"
                execution.parent.mkdir(parents=True)
                execution.touch()
                self.assertEqual(check_coverage.validate(root)["LINE"]["missed"], "2")
                report.write_text(report.read_text().replace('</package>', '<class name="example/Test"/></package>'))
                with self.assertRaisesRegex(ValueError, "non-production"):
                    check_coverage.validate(root)

    def test_module_without_jvm_tests_remains_measured(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = root / "module/build/classes/kotlin/main/example"
            main.mkdir(parents=True)
            (main / "Production.class").touch()
            report = root / check_coverage.REPORT
            report.parent.mkdir(parents=True)
            report.write_text('<report><package><class name="example/Production"/></package>'
                              '<counter type="LINE" covered="1" missed="2"/></report>')
            with patch.object(check_coverage, "MODULES", ("module",)):
                self.assertEqual(check_coverage.validate(root)["LINE"]["missed"], "2")

    def test_empty_and_missing_module_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / check_coverage.REPORT
            report.parent.mkdir(parents=True)
            report.write_text('<report><counter type="LINE" covered="0" missed="10"/></report>')
            with patch.object(check_coverage, "MODULES", ("missing",)):
                with self.assertRaisesRegex(ValueError, "production coverage inventory"):
                    check_coverage.validate(root)
            with patch.object(check_coverage, "MODULES", ()):
                with self.assertRaisesRegex(ValueError, "No covered production lines"):
                    check_coverage.validate(root)


if __name__ == "__main__":
    unittest.main()
