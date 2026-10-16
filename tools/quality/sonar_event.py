"""Select trusted CI events without reading or exposing an analysis token."""

import json
import os
from pathlib import Path


def eligible(event_name, ref, repository, actor, event):
    if repository != "NuaForma/NuaForma" or actor == "dependabot[bot]":
        return False
    if event_name in {"push", "workflow_dispatch"}:
        return ref in {"refs/heads/main", "refs/heads/develop"}
    if event_name != "pull_request":
        return False
    pr = event.get("pull_request", {})
    return (
        event.get("action") in {"opened", "synchronize", "reopened"}
        and pr.get("head", {}).get("repo", {}).get("full_name") == repository
        and pr.get("base", {}).get("repo", {}).get("full_name") == repository
        and pr.get("base", {}).get("ref") in {"main", "develop"}
        and pr.get("user", {}).get("login") != "dependabot[bot]"
    )


if __name__ == "__main__":
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    analyze = eligible(os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_REF"],
                       os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_ACTOR"], event)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"analyze={str(analyze).lower()}\n")
