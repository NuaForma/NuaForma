"""Run the independently compiled Maven PDF example inside an already qualified local runtime image."""
import argparse
import json
import pathlib
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    target = root / "examples/embedded/maven/pdf/target"
    assert (target / "classes/dev/nuaforma/examples/PdfExample.class").is_file()
    name = "nuaforma-embedded-qa-" + uuid.uuid4().hex
    def command(*arguments, timeout=30):
        return subprocess.run([args.docker, *arguments], capture_output=True, text=True, timeout=timeout, check=True)
    command("create", "--name", name, "--label", "dev.nuaforma.embedded-qa=" + name,
            "--init", "--network", "none", "--user", "1000:1000", "--read-only", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", "--security-opt", "seccomp=" + str(root / "deployment/pdf-runtime/seccomp.json"),
            "--pids-limit", "128", "--memory", "2g", "--cpus", "2", "--tmpfs", "/tmp:rw,size=512m,mode=1777",
            "--mount", "type=bind,source=" + str(target) + ",target=/consumer,readonly",
            "--mount", "type=bind,source=" + str(output) + ",target=/request",
            "--entrypoint", "/opt/java/openjdk/bin/java", args.image,
            "-Xmx512m", "-cp", "/consumer/classes:/consumer/dependency/*", "dev.nuaforma.examples.PdfExample",
            "/opt/nuaforma/bin/render-pdf", "/request/output.pdf")
    try:
        result = subprocess.run([args.docker, "start", "--attach", name], capture_output=True, text=True, timeout=180)
        state = json.loads(command("inspect", name).stdout)[0]["State"]
        (output / "process.json").write_text(json.dumps({"exitCode": result.returncode, "state": state,
            "stdout": result.stdout, "stderr": result.stderr}, indent=2), encoding="utf-8")
        assert state["ExitCode"] == 0 and not state["OOMKilled"] and not state["Running"], state
        assert (output / "output.pdf").is_file()
        record = {"image": args.image, "passed": True, "result": result.stdout.strip()}
        (output / "verification.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
        print(json.dumps(record))
    finally:
        label = command("inspect", name, "--format", '{{index .Config.Labels "dev.nuaforma.embedded-qa"}}').stdout.strip()
        assert label == name
        command("rm", "--force", name)


if __name__ == "__main__":
    main()
