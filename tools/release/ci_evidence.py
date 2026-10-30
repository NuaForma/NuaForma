"""Reuse complete qualification for identical tracked inputs, never manufacture a Sonar result."""
import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import subprocess
import zipfile

from maven_destination import destination

REPOSITORY = "NuaForma/NuaForma"
ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOWS = {"java": "build.yml", "native": "native-container.yml"}
LANES = {"java": ("17", "25"), "native": ("amd64", "arm64")}


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, timeout=60)


def api(path):
    return json.loads(command("gh", "api", "repos/" + REPOSITORY + "/" + path))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def native_input(path):
    return path.startswith(("nuaforma-", "deployment/pdf-runtime/", "tools/container/",
                            "tools/pdf-evaluation/", "gradle/")) or path in {
        "gradle.properties", "build.gradle.kts", "settings.gradle.kts", "gradlew",
        ".github/workflows/native-container.yml", "tools/release/ci_evidence.py"}


def input_fingerprint(kind, revision="HEAD", git=command):
    require(kind in LANES, "Unknown qualification kind")
    digest = hashlib.sha256()
    for entry in git("git", "ls-tree", "-rz", revision).split(b"\0"):
        if not entry:
            continue
        identity, path = entry.split(b"\t", 1)
        name = path.decode("utf-8")
        if name in {"README.md", "AGENTS.md"} or name.endswith("/README.md") or name.startswith(
                ("docs/", "release-notes/", "release-materials/", "assets/branding/")):
            continue
        if kind == "native" and not native_input(path.decode("utf-8")):
            continue
        if path == b"gradle.properties":
            content = git("git", "show", revision + ":gradle.properties")
            versions = re.findall(rb"(?m)^version=([^\r\n]+)", content)
            require(len(versions) == 1, "Require one source version")
            destination(versions[0].decode("ascii"))
            identity = identity.split(b" ", 1)[0] + b" normalized " + hashlib.sha256(
                re.sub(rb"(?m)^version=[^\r\n]+", b"version=<release>", content)).hexdigest().encode()
        digest.update(identity + b"\t" + path + b"\0")
    return digest.hexdigest()


def validate_run(run, jobs, kind):
    require(run.get("repository", {}).get("full_name") == REPOSITORY
            and run.get("path") == ".github/workflows/" + WORKFLOWS[kind]
            and run.get("event") in ("pull_request", "push", "workflow_dispatch"), "Foreign qualification run")
    for lane in LANES[kind]:
        name = f"Build (Java {lane})" if kind == "java" else f"Native container ({lane})"
        matches = [j for j in jobs if j.get("name") == name]
        require(len(matches) == 1 and matches[0].get("status") == "completed"
                and matches[0].get("conclusion") == "success", "Qualification jobs are not green")


def validate_receipts(run, receipts, kind, fingerprint, attempts=None):
    require(set(receipts) == set(LANES[kind]), "Incomplete qualification evidence")
    for lane, receipt in receipts.items():
        require(receipt.get("schema") == 1 and receipt.get("full") is True
                and receipt.get("repository") == REPOSITORY and receipt.get("run") == run["id"]
                and receipt.get("attempt") == (attempts or {}).get(lane, run.get("run_attempt", 1))
                and receipt.get("sourceCommit") == run["head_sha"]
                and receipt.get("kind") == kind and receipt.get("lane") == lane
                and receipt.get("fingerprint") == fingerprint
                and re.fullmatch(r"[a-f0-9]{40}", receipt.get("testedCommit", "")),
                "Qualification evidence does not bind the tested input/run/lane")
    require(len({r["testedCommit"] for r in receipts.values()}) == 1, "Qualification lanes tested different trees")


def read_receipt(artifact):
    require(not artifact.get("expired"), "Qualification evidence expired")
    require(0 < artifact.get("size_in_bytes", 0) <= 128 * 1024, "Oversized qualification artifact")
    raw = command("gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip")
    require(len(raw) <= 128 * 1024, "Oversized qualification evidence")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        require(archive.namelist() == ["qualification.json"] and archive.infolist()[0].file_size <= 16384,
                "Unexpected qualification archive")
        return json.loads(archive.read("qualification.json"))


def qualification(kind, revision="HEAD", preferred=None):
    """Only full successful roots qualify; reused wrapper jobs never issue full receipts."""
    fingerprint = input_fingerprint(kind, revision)
    if preferred:
        require(re.fullmatch(r"[1-9][0-9]*", str(preferred)), "Invalid qualification run ID")
    name = f"qualification-{kind}-{LANES[kind][0]}-{fingerprint}"
    candidates = api(f"actions/artifacts?name={name}&per_page=30")["artifacts"]
    identities = [a["workflow_run"]["id"] for a in sorted(candidates, key=lambda a: a["id"], reverse=True)
                  if not a.get("expired")]
    if preferred:
        identities = [int(preferred)] + [i for i in identities if i != int(preferred)]
    for identity in identities:
        run = api(f"actions/runs/{identity}")
        if run.get("conclusion") not in (None, "success"):
            continue
        artifacts = api(f"actions/runs/{run['id']}/artifacts?per_page=100")["artifacts"]
        receipts = {}
        for lane in LANES[kind]:
            name = f"qualification-{kind}-{lane}-{fingerprint}"
            matches = [a for a in artifacts if a["name"] == name and not a.get("expired")]
            if len(matches) != 1:
                break
            receipts[lane] = read_receipt(matches[0])
        if len(receipts) != len(LANES[kind]) or any(r.get("fingerprint") != fingerprint for r in receipts.values()):
            continue
        jobs = api(f"actions/runs/{run['id']}/jobs?per_page=100")
        require(jobs["total_count"] <= 100, "Qualification job pagination exceeds bound")
        validate_run(run, jobs["jobs"], kind)
        attempts = {lane: receipt.get("attempt") for lane, receipt in receipts.items()}
        historical = {}
        for lane, attempt in attempts.items():
            require(type(attempt) is int and 1 <= attempt <= run.get("run_attempt", 1), "Invalid lane attempt")
            if attempt != run.get("run_attempt", 1):
                if attempt not in historical:
                    historical[attempt] = api(f"actions/runs/{run['id']}/attempts/{attempt}/jobs?per_page=100")
                original = historical[attempt]
                require(original["total_count"] <= 100, "Historical job pagination exceeds bound")
                name = f"Build (Java {lane})" if kind == "java" else f"Native container ({lane})"
                matches = [j for j in original["jobs"] if j["name"] == name]
                require(len(matches) == 1 and matches[0].get("status") == "completed"
                        and matches[0].get("conclusion") == "success", "Original full lane did not succeed")
        validate_receipts(run, receipts, kind, fingerprint, attempts)
        for path in ("tools/release/ci_evidence.py", ".github/workflows/" + WORKFLOWS[kind]):
            require(command("git", "rev-parse", run["head_sha"] + ":" + path)
                    == command("git", "rev-parse", revision + ":" + path),
                    "Qualification producer differs from the selected workflow")
        require(subprocess.run(["git", "merge-base", "--is-ancestor", run["head_sha"], revision],
                               cwd=ROOT, capture_output=True).returncode == 0,
                "Qualification source is not part of the selected history")
        if kind == "java":
            checks = api(f"commits/{run['head_sha']}/check-runs?per_page=100&filter=latest")
            require(checks["total_count"] <= 100, "Check pagination exceeds bound")
            actual = [c for c in checks["check_runs"] if c["name"] == "SonarCloud Code Analysis"
                      and c["app"]["slug"] == "sonarqubecloud"]
            if not actual or max(actual, key=lambda c: c["id"])["status"] != "completed":
                continue
            latest = max(actual, key=lambda c: c["id"])
            require(latest["head_sha"] == run["head_sha"] and latest["conclusion"] == "success",
                    "Actual source Sonar Quality Gate is not green")
        return {"run": run["id"], "laneAttempts": attempts, "sourceCommit": run["head_sha"], "fingerprint": fingerprint,
                "kind": kind, "testedCommit": next(iter(receipts.values()))["testedCommit"]}
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("select", "record"))
    parser.add_argument("--kind", choices=LANES, required=True)
    parser.add_argument("--lane")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if args.action == "record":
        require(args.lane in LANES[args.kind], "Unknown qualification lane")
        record = {"schema": 1, "full": True, "repository": os.environ["GITHUB_REPOSITORY"],
                  "run": int(os.environ["GITHUB_RUN_ID"]), "attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
                  "sourceCommit": os.environ["HEAD_SOURCE"], "testedCommit": command("git", "rev-parse", "HEAD").decode().strip(),
                  "kind": args.kind, "lane": args.lane, "fingerprint": input_fingerprint(args.kind)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write("fingerprint=" + record["fingerprint"] + "\n")
        return
    can_reuse = (os.environ["GITHUB_EVENT_NAME"] in {"push", "pull_request"}
                 and os.environ.get("GITHUB_RUN_ATTEMPT", "1") == "1")
    result = None
    if can_reuse:
        try:
            result = qualification(args.kind)
        except (ValueError, subprocess.SubprocessError, zipfile.BadZipFile):
            print("Previous qualification is unavailable or invalid; run full checks")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("reuse=" + str(result is not None).lower() + "\n")
    print("Reuse complete qualification: " + json.dumps(result) if result else "Full qualification required")
    if result:
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(f"Reuse full {args.kind} qualification [run {result['run']}](https://github.com/{REPOSITORY}/actions/runs/{result['run']}). "
                          f"Input fingerprint `{result['fingerprint']}`; actual analyzed source `{result['sourceCommit']}`. No new Sonar analysis is claimed.\n")


if __name__ == "__main__":
    main()
