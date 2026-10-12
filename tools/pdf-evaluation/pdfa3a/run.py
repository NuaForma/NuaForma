"""Execute fixed PDF/A research operations in owned, isolated containers; no product changes."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import uuid


def execute(root, work, mode, docker, image, *, launcher=None, cli=None):
    name = "nuaforma-pdfa-research-" + uuid.uuid4().hex
    def command(*args, **kwargs):
        return subprocess.run([docker, *args], check=True, capture_output=True, text=True, **kwargs)
    mounts = [(launcher or root / "tools/pdf-evaluation/pdfa3a/launch.sh", "/research-launch.sh"),
              (cli or root / "build/pdfa-feasibility/installation/cli/bin", "/opt/nuaforma/research-verapdf"),
              (root / "build/pdfa-feasibility/probe", "/opt/nuaforma/research-probe")]
    arguments = ["create", "--name", name, "--label", "dev.nuaforma.pdfa-research=" + name,
                 "--init", "--network", "none", "--user", "1000:1000", "--read-only", "--cap-drop", "ALL",
                 "--security-opt", "no-new-privileges", "--security-opt", "seccomp=" + str(root / "deployment/pdf-runtime/seccomp.json"),
                 "--pids-limit", "128", "--memory", "2g", "--cpus", "2", "--tmpfs", "/tmp:rw,size=512m,mode=1777"]
    for path, target in mounts:
        arguments += ["--mount", "type=bind,source=" + str(path) + ",target=" + target + ",readonly"]
    if mode == "sandbox":
        for folder, target in (("application", "/app"), ("other", "/other")):
            arguments += ["--mount", "type=bind,source=" + str(root / "build/pdfa-feasibility/sentinels" / folder) + ",target=" + target + ",readonly"]
        arguments += ["--env", "SYNTHETIC_TENANT_SECRET=synthetic-only"]
    arguments += ["--mount", "type=bind,source=" + str(work) + ",target=/request", "--entrypoint", "/bin/sh", image, "/research-launch.sh", "/request", mode]
    command(*arguments, timeout=30)
    try:
        process = subprocess.run([docker, "start", "--attach", name], capture_output=True, text=True, timeout=150)
        state = json.loads(command("inspect", name, timeout=10).stdout)[0]["State"]
        assert not state["Running"] and not state["OOMKilled"], state
        record = {"mode": mode, "exitCode": state["ExitCode"], "dockerExitCode": process.returncode, "stderr": process.stderr[-8192:]}
        (work / (mode + "-process.json")).write_text(json.dumps(record, indent=2), encoding="utf-8")
        if (work / "result.txt").exists():
            shutil.copyfile(work / "result.txt", work / (mode + "-result.txt"))
        return record
    finally:
        label = command("inspect", name, "--format", '{{index .Config.Labels "dev.nuaforma.pdfa-research"}}', timeout=10).stdout.strip()
        assert label == name
        command("rm", "--force", name, timeout=30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--work", type=pathlib.Path, required=True)
    parser.add_argument("--mode", choices=("version", "help", "standard", "pdfa3b", "pdfa3b-ua", "validate-3a", "validate-3b", "validate-safety", "semantic", "inspect", "relabel", "negative", "negative-alt", "negative-unicode", "negative-lang", "negative-icc", "sandbox"), required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    args.work.mkdir(parents=True, exist_ok=True)
    for name in ("output", "fonts", "home", "cache", "profile", "tmp"):
        (args.work / name).mkdir(exist_ok=True)
    source = args.work / "input.docx"
    before = hashlib.sha256(source.read_bytes()).hexdigest() if source.exists() else None
    record = execute(root, args.work.resolve(), args.mode, args.docker, args.image)
    if before is not None:
        assert hashlib.sha256(source.read_bytes()).hexdigest() == before
    print(json.dumps(record))
    if record["exitCode"] != 0:
        raise SystemExit(record["exitCode"])


if __name__ == "__main__":
    main()
