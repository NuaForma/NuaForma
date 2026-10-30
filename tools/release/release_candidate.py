"""Prepare immutable release evidence from existing CI; never approve or publish."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.request

from check_bundle_review import check as check_bundle
from check_container_source_coverage import verify_inventory
from prepare_central_bundle import release_version
from verify_libraries import MODULES

from maven_destination import destination

ROOT = pathlib.Path(__file__).resolve().parents[2]

APPLICATIONS = {"server": "nuaforma", "playground": "nuaforma-playground"}
FINGERPRINT = "74317C6CE9C61B80A2A1C0B40F736E8D598CB08B"


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    return json.loads(command("gh", "api", "repos/NuaForma/NuaForma/" + path))


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            result.update(block)
    return result.hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as target:
        json.dump(value, target, indent=2)
        target.write("\n")


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def runtime_path(path):
    """Changes to these inputs require native CI again; release/docs do not."""
    return (path.startswith(("nuaforma-", "deployment/pdf-runtime/", "tools/container/",
                             "tools/pdf-evaluation/", "gradle/"))
            or path in {"gradle.properties", "build.gradle.kts", "settings.gradle.kts", "gradlew",
                        ".github/workflows/native-container.yml"})


def runtime_changes(paths, before_properties, after_properties):
    normalize = lambda value: re.sub(r"(?m)^version=.*$", "version=<release>", value)
    return [path for path in paths if runtime_path(path)
            and not (path == "gradle.properties" and normalize(before_properties) == normalize(after_properties))]


def require_checks(checks):
    expected = {"Build (Java 17)": "github-actions", "Build (Java 25)": "github-actions",
                "SonarCloud Code Analysis": "sonarqubecloud"}
    for name, app in expected.items():
        matching = [item for item in checks if item["name"] == name and item["app"]["slug"] == app]
        if not matching or matching[0]["conclusion"] != "success":
            raise ValueError("Current source check is missing or not green: " + name)


def require_native(run, jobs):
    if run["path"] != ".github/workflows/native-container.yml" or run["conclusion"] != "success":
        raise ValueError("Require successful native-container CI")
    for arch in ("amd64", "arm64"):
        if not any(job["name"] == f"Native container ({arch})" and job["conclusion"] == "success" for job in jobs):
            raise ValueError("Missing successful native CI architecture: " + arch)


def sources(baseline):
    assets = {}
    for application in APPLICATIONS:
        suffix = "-playground" if application == "playground" else ""
        record = read(ROOT / "release-materials" / (baseline + suffix + ".json"))
        if record["version"] != baseline or record["application"] != application:
            raise ValueError("Wrong baseline source review")
        for asset in record["sourceMaterials"]:
            prefix = f"https://github.com/NuaForma/NuaForma/releases/download/v{baseline}/nuaforma-sources-"
            if not asset["url"].startswith(prefix) or not re.fullmatch(r"[a-f0-9]{64}", asset["sha256"]):
                raise ValueError("Unexpected source material identity")
            if asset["url"] in assets and assets[asset["url"]] != asset["sha256"]:
                raise ValueError("Conflicting source review")
            assets[asset["url"]] = asset["sha256"]
    if len(assets) != 3:
        raise ValueError("Require Debian, both JVMs and third-party JAR source archives")
    for url, expected in assets.items():
        actual, size = hashlib.sha256(), 0
        with urllib.request.urlopen(url, timeout=120) as response:
            if not response.url.startswith("https://"):
                raise ValueError("Source retrieval must remain HTTPS")
            while block := response.read(1024 * 1024):
                size += len(block)
                if size > 2 * 1024**3:
                    raise ValueError("Source archive exceeds bound")
                actual.update(block)
        if not size or actual.hexdigest() != expected:
            raise ValueError("Public corresponding source differs from review")
    return [{"url": url, "sha256": value} for url, value in sorted(assets.items())]


def gate(version, baseline, native_run, output):
    release_version(version)
    release_version(baseline)
    if version == baseline:
        raise ValueError("A new immutable version is required")
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_REPOSITORY") != "NuaForma/NuaForma":
        raise ValueError("Release preparation is main-only")
    source = command("git", "rev-parse", "HEAD")
    if source != os.environ["GITHUB_SHA"] or command("git", "rev-parse", f"v{version}^{{commit}}") != source:
        raise ValueError("Signed release tag must identify this workflow revision")
    tag_object = command("git", "rev-parse", f"v{version}")
    if command("git", "cat-file", "-t", tag_object) != "tag":
        raise ValueError("Require an annotated signed tag")
    verification = api("git/tags/" + tag_object)["verification"]
    if not verification["verified"] or verification["reason"] != "valid":
        raise ValueError("Release tag signature is not verified")
    properties = (ROOT / "gradle.properties").read_text()
    if re.findall(r"(?m)^version=(.*)$", properties) != [version]:
        raise ValueError("Source version differs from requested release")
    checks = api(f"commits/{source}/check-runs?per_page=100")["check_runs"]
    require_checks(checks)
    native = api(f"actions/runs/{native_run}")
    require_native(native, api(f"actions/runs/{native_run}/jobs?per_page=100")["jobs"])
    tested = native["head_sha"]
    subprocess.run(["git", "merge-base", "--is-ancestor", tested, source], check=True)
    changed = command("git", "diff", "--name-only", tested, source).splitlines()
    differences = runtime_changes(changed, command("git", "show", tested + ":gradle.properties"), properties.strip())
    if differences:
        raise ValueError("Native CI no longer covers changed runtime inputs: " + ", ".join(differences))
    record = {"version": version, "sourceCommit": source, "tagObject": tag_object,
              "nativeCiRun": int(native_run), "nativeCiSource": tested, "sourceBaseline": baseline,
              "sourceMaterials": sources(baseline), "qualification": "existing-ci-no-release-retest",
              "runtimeReview": "Current inputs are Temurin 17.0.21+9 / 25.0.5+8. Review the source disposition of residual #51 findings for this release; historical evaluation acceptances are not reused"}
    write(output, record)
    print("Signed source, current JVM/Sonar CI, unchanged native CI inputs and public corresponding sources: PASS")


def match_foreign(gate_record):
    """Check source coverage before uploading shared build inputs to another runner."""
    roots = {"application-sha256.txt": "nuaforma-server/build/install/nuaforma-server/lib",
             "validator-sha256.txt": "nuaforma-docx-pdf/build/pdf-validation",
             "verapdf-sha256.txt": "nuaforma-docx-pdf/build/verapdf-validation"}
    for application in APPLICATIONS:
        paths = dict(roots)
        if application == "playground":
            paths["application-sha256.txt"] = "nuaforma-playground/build/install/nuaforma-playground/lib"
        actual = {scope + "/" + jar.name: digest(jar) for scope, directory in paths.items()
                  for jar in (ROOT / directory).glob("*.jar") if not jar.name.startswith("nuaforma-")}
        suffix = "-playground" if application == "playground" else ""
        baseline = read(ROOT / "release-materials" / (gate_record["sourceBaseline"] + suffix + ".json"))
        for platform in baseline["platforms"].values():
            if actual != platform["foreignJars"]:
                raise ValueError("Changed third-party JARs need a new source review before transfer")


def archive(gate_record, application, architecture, archive_path, inventory, output):
    suffix = "-playground" if application == "playground" else ""
    baseline = read(ROOT / "release-materials" / (gate_record["sourceBaseline"] + suffix + ".json"))
    verify_inventory(inventory, baseline["platforms"][architecture])
    image = read(inventory / "image.json")
    labels = image["Labels"]
    if (image["Architecture"] != architecture or image["Os"] != "linux" or image["User"] != "1000:1000"
            or labels.get("org.opencontainers.image.version") != gate_record["version"]
            or labels.get("org.opencontainers.image.revision") != gate_record["sourceCommit"]
            or labels.get("dev.nuaforma.application") != application):
        raise ValueError("Image identity, platform or non-root configuration differs from release")
    write(output, {"version": gate_record["version"], "sourceCommit": gate_record["sourceCommit"],
                   "application": application, "architecture": architecture,
                   "file": archive_path.name, "sha256": digest(archive_path), "config": image["Id"],
                   "sourceBaseline": gate_record["sourceBaseline"]})


def assemble(root, output):
    gate_record = read(root / "jvm/gate.json")
    bundle = read(root / "jvm/central.zip.json")
    hashes = {record["file"]: record["sha256"] for record in bundle["signatures"]}
    check_bundle(bundle, {"version": gate_record["version"], "libraryHashes": hashes})
    if digest(root / "jvm/central.zip") != bundle["sha256"]:
        raise ValueError("Central bundle changed in transit")
    images = []
    for application in APPLICATIONS:
        for architecture in ("amd64", "arm64"):
            directory = root / (application + "-" + architecture)
            image = read(directory / "archive.json")
            expected_file = f"{APPLICATIONS[application]}-container-{gate_record['version']}-{architecture}.tar.gz"
            if (image["application"] != application or image["architecture"] != architecture
                    or image["version"] != gate_record["version"] or image["sourceCommit"] != gate_record["sourceCommit"]
                    or image["sourceBaseline"] != gate_record["sourceBaseline"] or image["file"] != expected_file
                    or not re.fullmatch(r"sha256:[a-f0-9]{64}", image["config"])
                    or digest(directory / expected_file) != image["sha256"]):
                raise ValueError("Container identity or archive changed in transit")
            images.append(image)
    publication = destination(gate_record["version"])
    if publication["kind"] == "snapshot":
        raise ValueError("Use the dedicated snapshot workflow; release candidates must be immutable")
    record = dict(gate_record, libraryHashes=hashes, bundleSha256=bundle["sha256"], images=images,
                  mavenPublication=publication)
    write(output, record)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("gate", "match-foreign", "archive", "assemble"))
    parser.add_argument("--version")
    parser.add_argument("--baseline")
    parser.add_argument("--native-run")
    parser.add_argument("--gate", type=pathlib.Path)
    parser.add_argument("--application", choices=APPLICATIONS)
    parser.add_argument("--architecture", choices=("amd64", "arm64"))
    parser.add_argument("--archive", type=pathlib.Path)
    parser.add_argument("--inventory", type=pathlib.Path)
    parser.add_argument("--root", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if args.action == "gate":
        gate(args.version, args.baseline, args.native_run, args.output)
    elif args.action == "match-foreign":
        match_foreign(read(args.gate))
    elif args.action == "archive":
        archive(read(args.gate), args.application, args.architecture, args.archive, args.inventory, args.output)
    else:
        record = assemble(args.root, args.output)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a", encoding="utf-8") as target:
                target.write(f"## Ready for one final approval: {record['version']}\n\nSource: `{record['sourceCommit']}`.\n\n")
                target.write("All 24 signed library artifacts and four native image archives are built; full tests are reused from green CI.\n\n")
                target.write(record["runtimeReview"] + ".\n\n")
                target.write(f"Maven destination: `{record['mavenPublication']['repository']}` ({record['mavenPublication']['kind']}).\n\n")
                target.write("Approval authorizes publication to that Maven destination, both container indexes, GitHub release and the matching website. No rebuild follows approval.\n")
        print("Complete candidate manifest verified")


if __name__ == "__main__":
    main()
