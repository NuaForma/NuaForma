"""Export actual retained container legal materials from an owned stopped container; never publish."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    name = "nuaforma-notices-" + uuid.uuid4().hex
    def command(*items):
        return subprocess.check_output([args.docker, *items], text=True, stderr=subprocess.STDOUT, timeout=120).strip()
    image = json.loads(command("image", "inspect", args.image))[0]
    assert image["Config"].get("Labels", {}).get("dev.nuaforma.validation-boundary") == "container", "Use matching source tools to audit older image boundaries"
    command("create", "--name", name, "--label", "dev.nuaforma.notices=" + name, image["Id"])
    sources = {
        "outer-doc": "/usr/share/doc", "outer-common-licenses": "/usr/share/common-licenses",
        "java-legal": "/opt/java/openjdk/legal", "java-release": "/opt/java/openjdk/release",
        "runtime-licenses": "/usr/share/nuaforma/licenses", "runtime-NOTICE": "/usr/share/nuaforma/NOTICE",
        "source-instructions": "/usr/share/nuaforma/SOURCES.md",
        "application-materials": "/opt/nuaforma/application/third-party-materials",
        "verapdf-materials": "/opt/nuaforma/verapdf-validation/materials",
        "verapdf-licenses": "/opt/nuaforma/verapdf-validation/licenses",
    }
    if details_layout := image["Config"].get("Labels", {}).get("dev.nuaforma.jvm-layout"):
        assert details_layout == "server25-validator17", "Unreviewed JVM layout"
        sources.update({"server-java-legal": "/opt/java/server/legal", "server-java-release": "/opt/java/server/release"})
    try:
        for target, source in sources.items():
            with (args.output / (target + ".tar")).open("xb") as destination:
                subprocess.run([args.docker, "cp", name + ":" + source, "-"], stdout=destination,
                               stderr=subprocess.PIPE, check=True, timeout=120)
    finally:
        assert command("inspect", name, "--format", '{{index .Config.Labels "dev.nuaforma.notices"}}') == name
        command("rm", name)
    records = []
    for file in sorted(args.output.rglob("*")):
        if file.is_symlink():
            records.append({"path": file.relative_to(args.output).as_posix(), "symlink": str(file.readlink())})
        elif file.is_file():
            records.append({"path": file.relative_to(args.output).as_posix(), "sha256": hashlib.sha256(file.read_bytes()).hexdigest()})
    (args.output / "inventory.json").write_text(json.dumps({"image": image["Id"], "files": records, "auditApproved": False}, indent=2), encoding="utf-8")
    print("Retained", len(records), "actual legal/source records; review remains required")


if __name__ == "__main__": main()
