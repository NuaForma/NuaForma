"""Diagnose only a generated QA document using the candidate's unchanged isolation."""
import argparse
import json
import pathlib
import shutil
import subprocess
import uuid
from verify_isolation_config import verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--document", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--apparmor", choices=("nuaforma-runtime",))
    parser.add_argument("--docker", default="docker")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    work = args.output.resolve() / "work"
    work.mkdir(mode=0o777)
    work.chmod(0o777)
    for part in ("output", "fonts", "home", "cache", "profile", "tmp"):
        path = work / part
        path.mkdir(mode=0o777)
        path.chmod(0o777)
    shutil.copyfile(args.document, work / "input.docx")
    (work / "fonts.conf").write_text('<fontconfig><include>/etc/fonts/fonts.conf</include><cachedir>/work/cache/fontconfig</cachedir></fontconfig>', encoding="utf-8")
    records = []
    for mode in ("convert-pdfa-3b", "validate", "validate-pdfa-3b"):
        name = "nuaforma-synthetic-stage-" + uuid.uuid4().hex
        def command(*values):
            return subprocess.check_output([args.docker, *values], text=True, stderr=subprocess.STDOUT, timeout=90).strip()
        policy = ["--security-opt", "apparmor=" + args.apparmor] if args.apparmor else []
        command("create", "--name", name, "--label", "dev.nuaforma.synthetic-stage=" + name,
                "--init", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--security-opt", "seccomp=" + str(pathlib.Path("deployment/pdf-runtime/seccomp.json").resolve()),
                "--pids-limit", "128", "--memory", "2g", "--cpus", "2", "--network", "none",
                "--tmpfs", "/tmp:rw,size=512m,mode=1777", *policy,
                "--mount", "type=bind,source=" + str(work) + ",target=/tmp/nuaforma-synthetic-stage",
                "--entrypoint", "/opt/nuaforma/bin/render-pdf", args.image,
                "/tmp/nuaforma-synthetic-stage", "8388608", "11", mode)
        try:
            verify(json.loads(command("inspect", name))[0], args.apparmor)
            result = subprocess.run([args.docker, "start", "--attach", name], capture_output=True, text=True, timeout=90)
            exit_code = int(command("inspect", name, "--format", "{{.State.ExitCode}}"))
            record = {"mode": mode, "exitCode": exit_code, "stdout": result.stdout[:8192], "stderr": result.stderr[:8192]}
            print(json.dumps(record), flush=True)
            records.append(record)
            if exit_code != 0:
                break
        finally:
            assert command("inspect", name, "--format", '{{index .Config.Labels "dev.nuaforma.synthetic-stage"}}') == name
            command("rm", "-f", name)
    (args.output / "diagnostics.json").write_text(json.dumps(records, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
