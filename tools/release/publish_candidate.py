"""Publish one fully prepared candidate after the single protected environment approval."""
import argparse
import base64
import hashlib
import json
import os
import pathlib
import subprocess
import time
import urllib.error
import urllib.request

from central_portal import BASE, open_request, upload
from central_publish import publish
from check_multiarch_index import check as check_index
from release_candidate import APPLICATIONS, assemble, command, digest, read, write
from verify_libraries import MODULES


def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


def validated(identity, token, opener=open_request, sleep=time.sleep):
    """Only read status repeatedly; upload and publish are never retried."""
    for _ in range(40):
        request = urllib.request.Request(BASE + "status?id=" + identity, data=b"", method="POST",
                                         headers={"Authorization": "Bearer " + token})
        with opener(request, timeout=60) as response:
            data = response.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ValueError("Central status exceeds bound")
        status = json.loads(data)
        if status.get("deploymentId") != identity:
            raise ValueError("Central status belongs to another deployment")
        state = status.get("deploymentState")
        if state == "VALIDATED":
            return
        if state not in {"PENDING", "VALIDATING"}:
            raise ValueError("Central validation did not succeed: " + str(state))
        sleep(10)
    raise TimeoutError("Central validation remains pending; retain deployment identity for recovery")


def verify_public_libraries(record, opener=urllib.request.urlopen, sleep=time.sleep):
    """Anonymous public identity check, not a second consumer qualification."""
    remaining = dict(record["libraryHashes"])
    for _ in range(90):
        for name, expected in list(remaining.items()):
            module = next(module for module in MODULES if name.startswith(module + "-" + record["version"] + ".")
                          or name.startswith(module + "-" + record["version"] + "-"))
            url = f"https://repo.maven.apache.org/maven2/dev/nuaforma/{module}/{record['version']}/{name}"
            try:
                with opener(url, timeout=60) as response:
                    data = response.read(128 * 1024 * 1024 + 1)
                if len(data) > 128 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != expected:
                    raise ValueError("Public Maven artifact differs from approved candidate")
                del remaining[name]
            except urllib.error.HTTPError as error:
                if error.code not in {404, 429, 500, 502, 503, 504}:
                    raise
            except urllib.error.URLError:
                pass
        if not remaining:
            return {"version": record["version"], "artifacts": len(record["libraryHashes"]), "publicBytesMatch": True}
        sleep(10)
    raise TimeoutError("Publication requested but public Maven propagation is incomplete; do not upload again")


def check_loaded(image, record, details):
    labels = details["Config"].get("Labels", {})
    if (details["Id"] != image["config"] or details["Architecture"] != image["architecture"]
            or details["Os"] != "linux" or details["Config"].get("User") != "1000:1000"
            or labels.get("org.opencontainers.image.version") != record["version"]
            or labels.get("org.opencontainers.image.revision") != record["sourceCommit"]
            or labels.get("dev.nuaforma.application") != image["application"]):
        raise ValueError("Loaded image identity differs from approved candidate")


def load_images(root, record):
    for image in record["images"]:
        name = APPLICATIONS[image["application"]]
        local = f"{name}:{record['version']}-{image['architecture']}-archive"
        path = root / (image["application"] + "-" + image["architecture"]) / image["file"]
        run("docker", "load", "--input", str(path))
        details = json.loads(command("docker", "image", "inspect", local))[0]
        check_loaded(image, record, details)


def require_absent(record):
    # All image tags are checked before the first irreversible publication.
    for name in APPLICATIONS.values():
        reference = f"ghcr.io/nuaforma/{name}:{record['version']}"
        for suffix in ("", "-amd64", "-arm64"):
            run("python3", "tools/release/ensure_tag_absent.py", "--reference", reference + suffix)
    result = subprocess.run(["gh", "api", "repos/NuaForma/NuaForma/releases/tags/v" + record["version"]],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode == 0 or "HTTP 404" not in result.stderr:
        raise ValueError("Release already exists or its absence cannot be established; inspect before recovery")
    # Reject an already visible Maven version; require every expected name to be absent.
    for module in MODULES:
        url = f"https://repo.maven.apache.org/maven2/dev/nuaforma/{module}/{record['version']}/{module}-{record['version']}.pom"
        try:
            with urllib.request.urlopen(url, timeout=30):
                raise ValueError("Maven version already exists; do not upload again")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise


def publish_images(root, record, receipts):
    result = {}
    for application, name in APPLICATIONS.items():
        reference = f"ghcr.io/nuaforma/{name}:{record['version']}"
        descriptors = {}
        for arch in ("amd64", "arm64"):
            run("docker", "tag", f"{name}:{record['version']}-{arch}-archive", reference + "-" + arch)
            run("docker", "push", reference + "-" + arch)
            descriptors[arch] = json.loads(command("docker", "manifest", "inspect", "--verbose", reference + "-" + arch))
            write(receipts / f"{application}-{arch}-published.json", descriptors[arch])
        run("docker", "manifest", "create", reference, reference + "-amd64", reference + "-arm64")
        run("docker", "manifest", "push", reference)
        index = json.loads(command("docker", "manifest", "inspect", reference))
        configs = {image["architecture"]: image["config"].removeprefix("sha256:")
                   for image in record["images"] if image["application"] == application}
        check_index(index, descriptors, configs)
        write(receipts / (application + "-index.json"), index)
        raw = command("docker", "buildx", "imagetools", "inspect", reference, "--format", "{{json .Manifest}}")
        descriptor = json.loads(raw)
        index_digest = descriptor["digest"]
        if not index_digest.startswith("sha256:") or len(index_digest) != 71:
            raise ValueError("Unexpected published index digest")
        result[application] = {"reference": reference, "digest": index_digest}
        if "-" not in record["version"]:
            run("docker", "manifest", "create", f"ghcr.io/nuaforma/{name}:latest", reference + "-amd64", reference + "-arm64", "--amend")
            run("docker", "manifest", "push", f"ghcr.io/nuaforma/{name}:latest")
    write(receipts / "containers.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, required=True)
    parser.add_argument("--confirm-publish", action="store_true", required=True)
    args = parser.parse_args()
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_REPOSITORY") != "NuaForma/NuaForma":
        raise ValueError("Publication is main-only")
    root = args.root
    receipts = root / "receipts"
    receipts.mkdir(exist_ok=False)
    record = read(root / "verified.json")
    # Reconstruct approval binding from the exact transferred bytes; no rebuilding.
    reconstructed = assemble(root, receipts / "candidate.json")
    if reconstructed != record or record["sourceCommit"] != os.environ["GITHUB_SHA"]:
        raise ValueError("Approved candidate differs from current source")
    require_absent(record)
    load_images(root, record)
    token = base64.b64encode((os.environ["MAVEN_CENTRAL_USERNAME"] + ":" + os.environ["MAVEN_CENTRAL_PASSWORD"]).encode()).decode("ascii")
    deployment = upload(root / "jvm/central.zip", record["version"], record["bundleSha256"], token)
    write(receipts / "deployment.json", deployment)
    validated(deployment["deploymentId"], token)
    requested = publish(deployment["deploymentId"], record, token)
    write(receipts / "maven-publish-request.json", requested)
    # Registry password remains process memory/stdin; never pass it as a command argument.
    run("docker", "login", "ghcr.io", "--username", os.environ["GITHUB_ACTOR"], "--password-stdin",
        input=os.environ["REGISTRY_TOKEN"].encode())
    containers = publish_images(root, record, receipts)
    public = verify_public_libraries(record)
    write(receipts / "maven-public.json", public)
    notes = pathlib.Path("release-notes") / (record["version"] + ".md")
    if not notes.is_file():
        raise ValueError("Reviewed release notes are missing")
    run("gh", "release", "create", "v" + record["version"], "--repo", "NuaForma/NuaForma",
        "--verify-tag", "--title", "NuaForma " + record["version"], "--notes-file", str(notes),
        *( ["--prerelease"] if "-" in record["version"] else []))
    assets = [str(path) for path in sorted(receipts.glob("*.json"))]
    run("gh", "release", "upload", "v" + record["version"], "--repo", "NuaForma/NuaForma", *assets)
    print("Maven public bytes, both native image indexes and GitHub release: PASS")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as target:
            target.write("## Published; matching website can now be promoted\n\n")
            for application, image in containers.items():
                target.write(f"- {application}: `{image['reference']}@{image['digest']}`\n")
            target.write("\nAll 24 anonymously retrieved Maven artifacts match the approved candidate.\n")


if __name__ == "__main__":
    main()
