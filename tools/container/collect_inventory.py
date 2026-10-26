"""Export release-review metadata without redistributing a container or dependency binaries."""
import argparse
import json
import pathlib
import subprocess
import uuid


def collect(image, destination, docker="docker"):
    destination.mkdir(parents=True, exist_ok=False)
    def command(*args):
        return subprocess.check_output([docker, *args], text=True, stderr=subprocess.STDOUT, timeout=60).strip()
    details = json.loads(command("image", "inspect", image))[0]
    assert details["Config"].get("Labels", {}).get("dev.nuaforma.validation-boundary") == "container", "Use the matching source tools for older image boundaries"
    metadata = {key: details[key] for key in ("Id", "Architecture", "Os")}
    metadata["User"] = details["Config"]["User"]
    metadata["Labels"] = details["Config"].get("Labels", {})
    (destination / "image.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    name = "nuaforma-inventory-" + uuid.uuid4().hex
    command("create", "--name", name, "--label", "dev.nuaforma.inventory=" + name, image)
    try:
        for file in ("outer-packages.tsv", "validator-sha256.txt", "verapdf-sha256.txt", "application-sha256.txt"):
            command("cp", name + ":/usr/share/nuaforma/" + file, str(destination / file))
        if metadata["Labels"].get("dev.nuaforma.jvm-layout") == "server25-validator17":
            command("cp", name + ":/usr/share/nuaforma/java-sha256.txt", str(destination / "java-sha256.txt"))
    finally:
        assert command("inspect", name, "--format", '{{index .Config.Labels "dev.nuaforma.inventory"}}') == name
        command("rm", name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--destination", type=pathlib.Path, required=True)
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    collect(args.image, args.destination, args.docker)
