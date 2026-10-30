import copy
import unittest

from snapshot_gate import automatic_version, successful_run, wait_for_sonar


class AutomaticSnapshotTest(unittest.TestCase):
    def test_publication_version_advances_a_stable_baseline_and_preserves_development_base(self):
        for source, expected in (("5.0.0", "5.0.1-SNAPSHOT"), ("5.0.9", "5.0.10-SNAPSHOT"),
                                 ("0.3.0-alpha.5", "0.3.0-SNAPSHOT"), ("5.1.0-alpha.1", "5.1.0-SNAPSHOT"), ("5.1.0-SNAPSHOT", "5.1.0-SNAPSHOT")):
            self.assertEqual(expected, automatic_version(source))
        for bad in ("../5.0.0", "5.0.0-snapshot", "5.0.0+mutable"):
            with self.assertRaises(ValueError):
                automatic_version(bad)

    def test_only_exact_push_source_run_and_all_completed_ci_jobs_allow_publication(self):
        run = {"id": 123, "head_sha": "a" * 40, "head_branch": "develop", "event": "push",
               "path": ".github/workflows/build.yml", "repository": {"full_name": "NuaForma/NuaForma"}}
        jobs = [{"name": name, "status": "completed", "conclusion": "success"} for name in
                ("Build (Java 17)", "Build (Java 25)")]
        successful_run(run, jobs, "NuaForma/NuaForma", "a" * 40, "123")
        for field, value in (("id", 124), ("head_sha", "b" * 40), ("head_branch", "main"),
                             ("event", "pull_request"), ("path", ".github/workflows/other.yml"),
                             ("repository", {"full_name": "other/fork"})):
            with self.subTest(field=field), self.assertRaises(ValueError):
                successful_run(dict(run, **{field: value}), jobs, "NuaForma/NuaForma", "a" * 40, "123")
        for index in range(2):
            for status, conclusion in (("in_progress", None), ("completed", "failure"), ("completed", "skipped")):
                bad = copy.deepcopy(jobs)
                bad[index].update(status=status, conclusion=conclusion)
                with self.assertRaises(ValueError):
                    successful_run(run, bad, "NuaForma/NuaForma", "a" * 40, "123")
        for bad in (jobs[:1], jobs + [jobs[0]]):
            with self.assertRaises(ValueError):
                successful_run(run, bad, "NuaForma/NuaForma", "a" * 40, "123")


class SonarGateTest(unittest.TestCase):
    @staticmethod
    def check(**changes):
        value = {"id": 10, "name": "SonarCloud Code Analysis", "head_sha": "a" * 40,
                 "app": {"slug": "sonarqubecloud"}, "status": "completed", "conclusion": "success"}
        value.update(changes)
        return value

    @staticmethod
    def response(*checks):
        return {"total_count": len(checks), "check_runs": list(checks)}

    def test_waits_for_real_analysis_after_scanner_job_and_uses_no_generic_status(self):
        generic = self.check(name="SonarCloud", app={"slug": "github-advanced-security"})
        replies = iter((self.response(generic), self.response(self.check(status="in_progress", conclusion=None)),
                        self.response(self.check())))
        pauses = []
        wait_for_sonar(lambda: next(replies), "a" * 40, attempts=3, pause=pauses.append)
        self.assertEqual([10, 10], pauses)

    def test_missing_pending_or_imposter_analysis_times_out_without_publication(self):
        for checks in ((), (self.check(status="queued", conclusion=None),),
                       (self.check(app={"slug": "github-actions"}),)):
            with self.subTest(checks=checks), self.assertRaises(ValueError):
                wait_for_sonar(lambda: self.response(*checks), "a" * 40, attempts=1,
                               pause=lambda seconds: self.fail("Unexpected sleep"))

    def test_failed_cancelled_skipped_wrong_revision_and_older_green_checks_do_not_pass(self):
        for changed in ({"conclusion": "failure"}, {"conclusion": "cancelled"}, {"conclusion": "skipped"},
                        {"head_sha": "b" * 40}):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                wait_for_sonar(lambda: self.response(self.check(**changed)), "a" * 40, attempts=2,
                               pause=lambda seconds: self.fail("Failure must stop immediately"))
        with self.assertRaises(ValueError):
            wait_for_sonar(lambda: self.response(self.check(id=9), self.check(id=10, conclusion="failure")),
                           "a" * 40, attempts=1)
        with self.assertRaises(ValueError):
            wait_for_sonar(lambda: {"total_count": 101, "check_runs": [self.check()]}, "a" * 40, attempts=1)
