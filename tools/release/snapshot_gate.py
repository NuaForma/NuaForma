"""Develop-only snapshot gate: exact signed checkout and successful source CI."""
import argparse
import json
import os
import re
import subprocess
import time

from maven_destination import destination, require


def automatic_version(version):
    kind = destination(version)["kind"]
    base = version.split("-", 1)[0]
    if kind == "stable":
        major, minor, patch = base.split(".")
        base = major + "." + minor + "." + str(int(patch) + 1)
    return base + "-SNAPSHOT"


def successful_run(run, jobs, repository, revision, identity):
    require(run.get("id") == int(identity) and run.get("head_sha") == revision
            and run.get("head_branch") == "develop" and run.get("event") == "push"
            and run.get("path") == ".github/workflows/build.yml"
            and run.get("repository", {}).get("full_name") == repository,
            "Require this exact develop Build run, not another source/run/workflow")
    for name in ("Build (Java 17)", "Build (Java 25)"):
        matches = [job for job in jobs if job.get("name") == name]
        require(len(matches) == 1 and matches[0].get("status") == "completed"
                and matches[0].get("conclusion") == "success", "Both Java builds must finish successfully before publication")


def successful_checks(checks, sonar=False):
    expected = {("Build (Java 17)", "github-actions"), ("Build (Java 25)", "github-actions")}
    if sonar:
        expected.add(("SonarCloud Code Analysis", "sonarqubecloud"))
    for name, app in expected:
        matches = [item for item in checks if item.get("name") == name and item.get("app", {}).get("slug") == app]
        require(bool(matches), "Require the real Java 17/25 CI and, for NuaForma, actual Sonar analysis on this exact commit")
        latest = max(matches, key=lambda item: item["id"])
        require(latest.get("status") == "completed" and latest.get("conclusion") == "success", "Required exact-commit CI is not green")


def wait_for_sonar(fetch, revision, attempts=61, pause=time.sleep):
    for attempt in range(attempts):
        data = fetch()
        require(data["total_count"] <= 100, "Too many check runs; explicit pagination review required")
        matches = [item for item in data["check_runs"] if item.get("name") == "SonarCloud Code Analysis"
                   and item.get("app", {}).get("slug") == "sonarqubecloud"]
        if matches:
            latest = max(matches, key=lambda item: item["id"])
            require(latest.get("head_sha") == revision, "Sonar analysis must identify this exact source")
            if latest.get("status") == "completed":
                require(latest.get("conclusion") == "success", "Actual Sonar analysis/Quality Gate is not green")
                return
        if attempt + 1 < attempts:
            pause(10)
    raise ValueError("Actual Sonar analysis/Quality Gate did not finish; no publication attempted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, choices=("NuaForma/NuaForma", "NuaForma/image-comparison"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--ci-run")
    args = parser.parse_args()
    require(destination(args.version)["kind"] == "snapshot", "Require canonical X.Y.Z-SNAPSHOT")
    require(os.environ.get("GITHUB_REPOSITORY") == args.repository and os.environ.get("GITHUB_REF") == "refs/heads/develop",
            "Snapshot publishing is restricted to this repository's develop branch")
    revision = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[a-f0-9]{40}", revision), "Require exact source revision")

    def run(*arguments):
        result = subprocess.run(arguments, capture_output=True, text=True, timeout=60)
        require(result.returncode == 0, "Cannot establish reviewed source/CI identity; no credentials loaded or publication attempted")
        return result.stdout.strip()

    require(run("git", "rev-parse", "HEAD") == revision, "Snapshot source checkout differs from the selected revision")
    commit = json.loads(run("gh", "api", "repos/" + args.repository + "/commits/" + revision))
    verification = commit.get("commit", {}).get("verification", {})
    require(verification.get("verified") is True and verification.get("reason") == "valid", "Require GitHub-verified signed snapshot source")
    if args.ci_run:
        require(re.fullmatch(r"[1-9][0-9]*", args.ci_run) and args.ci_run == os.environ.get("GITHUB_RUN_ID"), "Require the current calling CI run")
        endpoint = "repos/" + args.repository + "/actions/runs/" + args.ci_run
        current = json.loads(run("gh", "api", endpoint))
        jobs = json.loads(run("gh", "api", endpoint + "/jobs?per_page=100"))
        require(jobs["total_count"] <= 100, "Too many CI jobs; explicit pagination review required")
        successful_run(current, jobs["jobs"], args.repository, revision, args.ci_run)
        if args.repository == "NuaForma/NuaForma":
            wait_for_sonar(lambda: json.loads(run("gh", "api", "repos/" + args.repository
                                                 + "/commits/" + revision + "/check-runs?per_page=100&filter=latest")), revision)
    else:
        checks = json.loads(run("gh", "api", "repos/" + args.repository + "/commits/" + revision + "/check-runs?per_page=100&filter=latest"))
        require(checks["total_count"] <= 100, "Too many check runs; explicit pagination review required")
        successful_checks(checks["check_runs"], sonar=args.repository == "NuaForma/NuaForma")
    print("Exact signed develop source and required Java CI verified; snapshot destination: " + destination(args.version)["repository"])


if __name__ == "__main__":
    main()
