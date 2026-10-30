"""Manual develop-only snapshot gate: exact checkout, verified source and successful CI."""
import argparse
import json
import os
import re
import subprocess

from maven_destination import destination, require


def successful_checks(checks, sonar=False):
    expected = {("Build (Java 17)", "github-actions"), ("Build (Java 25)", "github-actions")}
    if sonar:
        expected.add(("SonarCloud Code Analysis", "sonarqubecloud"))
    for name, app in expected:
        matches = [item for item in checks if item.get("name") == name and item.get("app", {}).get("slug") == app]
        require(bool(matches), "Require the real Java 17/25 CI and, for NuaForma, actual Sonar analysis on this exact commit")
        latest = max(matches, key=lambda item: item["id"])
        require(latest.get("status") == "completed" and latest.get("conclusion") == "success", "Required exact-commit CI is not green")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, choices=("NuaForma/NuaForma", "NuaForma/image-comparison"))
    parser.add_argument("--version", required=True)
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
    checks = json.loads(run("gh", "api", "repos/" + args.repository + "/commits/" + revision + "/check-runs?per_page=100&filter=latest"))
    require(checks["total_count"] <= 100, "Too many check runs; explicit pagination review required")
    successful_checks(checks["check_runs"], sonar=args.repository == "NuaForma/NuaForma")
    print("Exact signed develop source and required Java CI verified; snapshot destination: " + destination(args.version)["repository"])


if __name__ == "__main__":
    main()
