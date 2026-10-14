"""Run synthetic negative isolation probes with the candidate's own sandbox."""
import argparse
import pathlib
import subprocess
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--probe-image", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--seccomp", type=pathlib.Path, default=pathlib.Path("deployment/pdf-runtime/seccomp.json"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    owner = "nuaforma-isolation-" + uuid.uuid4().hex
    label = "dev.nuaforma.isolation"

    def command(*values):
        return subprocess.check_output([args.docker, *values], text=True, stderr=subprocess.STDOUT, timeout=60).strip()

    def remove(name):
        assert command("inspect", name, "--format", '{{index .Config.Labels "' + label + '"}}') == owner
        command("rm", "-f", name)

    with tempfile.TemporaryDirectory(dir=args.output.resolve(), prefix="synthetic-") as temporary:
        root = pathlib.Path(temporary)
        work = root / "work"
        outer = root / "outer"
        work.mkdir(mode=0o777)
        outer.mkdir(mode=0o755)
        work.chmod(0o777)
        (work / "visible.txt").write_text("synthetic own request", encoding="utf-8")
        (outer / "secret.txt").write_text("synthetic other request", encoding="utf-8")
        probe = owner + "-probe"
        command("create", "--name", probe, "--label", label + "=" + owner,
                "--entrypoint", "/bin/true", args.probe_image)
        try:
            command("cp", probe + ":/src/sandbox-probe", str(work / "sandbox-probe"))
        finally:
            remove(probe)
        (work / "sandbox-probe").chmod(0o755)
        script = pathlib.Path("tools/pdf-evaluation/run_sandbox_probe.sh").resolve()
        name = owner + "-runtime"
        command("create", "--name", name, "--label", label + "=" + owner, "--init",
                "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--security-opt", "seccomp=" + str(args.seccomp.resolve()),
                "--pids-limit", "128", "--memory", "2g", "--cpus", "2",
                "--tmpfs", "/tmp:rw,size=64m,mode=1777", "--network", "none",
                "--mount", "type=bind,source=" + str(work) + ",target=/work",
                "--mount", "type=bind,source=" + str(outer) + ",target=/app,readonly",
                "--mount", "type=bind,source=" + str(outer) + ",target=/other,readonly",
                "--mount", "type=bind,source=" + str(script) + ",target=/probe.sh,readonly",
                "--env", "SYNTHETIC_TENANT_SECRET=synthetic-only", "--entrypoint", "/bin/sh",
                args.image, "/probe.sh")
        try:
            result = command("start", "--attach", name)
            assert command("inspect", name, "--format", "{{.State.ExitCode}}") == "0", result
            assert len([line for line in result.splitlines() if line.startswith("PASS ")]) == 20, result
            (args.output / "isolation.txt").write_text(result + "\n", encoding="utf-8")
            print(result)
        finally:
            remove(name)


if __name__ == "__main__":
    main()
