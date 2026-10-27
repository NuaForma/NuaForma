"""Fail closed unless the reviewed GitHub environment requires the sole maintainer's approval."""
import argparse
import json
import urllib.request
import os

if not __debug__:
    raise RuntimeError("Release verification must run without Python optimization")


def check(environment, policies, name="maven-central"):
    assert name in {"maven-central", "ghcr-release", "release"}
    assert environment["name"] == name and environment["can_admins_bypass"] is False
    reviewers = [rule for rule in environment["protection_rules"] if rule["type"] == "required_reviewers"]
    assert len(reviewers) == 1 and reviewers[0]["prevent_self_review"] is False
    identities = [(item["type"], item["reviewer"]["login"]) for item in reviewers[0]["reviewers"]]
    assert identities == [("User", "mrhoeve")], "Unexpected required reviewer configuration"
    assert environment["deployment_branch_policy"] == {"protected_branches": False, "custom_branch_policies": True}
    assert policies["total_count"] == 1
    assert [(item["type"], item["name"]) for item in policies["branch_policies"]] == [("branch", "main")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", choices=("maven-central", "ghcr-release", "release"), default="maven-central")
    args = parser.parse_args()
    assert os.environ.get("GITHUB_REPOSITORY") == "NuaForma/NuaForma" and os.environ.get("GITHUB_REF") == "refs/heads/main"
    def read(suffix):
        request = urllib.request.Request("https://api.github.com/repos/NuaForma/NuaForma/environments/" + args.environment + suffix,
            headers={"Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    check(read(""), read("/deployment-branch-policies"), args.environment)
    print("Release environment protection verified")


if __name__ == "__main__":
    main()
