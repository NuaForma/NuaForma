"""Run the packaged clients against the released, isolated local container."""
import argparse
import importlib.util
import json
import pathlib
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/container"))
spec = importlib.util.spec_from_file_location("qualification", ROOT / "tools/container/qualify_service.py")
qualification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualification)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--website-directory", type=pathlib.Path, required=True, help="Explicit NuaForma website checkout; no implicit sibling lookup")
    parser.add_argument("--node", required=True)
    parser.add_argument("--bruno", type=pathlib.Path, required=True, help="Official Bruno CLI bin/bru.js")
    parser.add_argument("--newman", type=pathlib.Path, required=True, help="Official Newman bin/newman.js")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    scenario_count = len(json.loads((args.website_directory / "site/downloads/scenarios.json").read_text(encoding="utf-8")))
    request_count = 5 + scenario_count
    with zipfile.ZipFile(args.website_directory / "site/downloads/nuaforma-complete-kit.zip") as archive:
        assert all(not pathlib.PurePosixPath(name).is_absolute() and ".." not in pathlib.PurePosixPath(name).parts for name in archive.namelist())
        archive.extractall(args.output / "extracted")
    runtime = qualification.Runtime(args)
    try:
        base = runtime.connect()
        status, _, body = qualification.http(base, "/api/v1/capabilities")
        assert status == 200
        assert "pdf" in next(p for p in json.loads(body)["providers"] if p["id"] == "nuaforma-docx-pdf")["available"]["writeFormats"]
        clients = args.output / "extracted/Collections"
        commands = [
            ("Bruno", [args.node, str(args.bruno.resolve()), "run", "--env-var", "baseUrl=" + base, "--reporter-json", str(args.output / "bruno-results.json")]),
            ("Postman", [args.node, str(args.newman.resolve()), "run", "NuaForma.postman_collection.json", "--working-dir", ".", "--env-var", "baseUrl=" + base, "--timeout-request", "180000", "--reporters", "cli,json", "--reporter-json-export", str(args.output / "postman-results.json")]),
        ]
        for name, command in commands:
            print("Running", name, "from extracted complete kit", flush=True)
            result = subprocess.run(command, cwd=clients / name, text=True, encoding="utf-8", errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=900)
            (args.output / (name.lower() + ".log")).write_text(result.stdout, encoding="utf-8")
            print(name, "exit code:", result.returncode, "(full output saved to log)", flush=True)
            assert result.returncode == 0, (name, result.returncode)
        postman = json.loads((args.output / "postman-results.json").read_text(encoding="utf-8"))
        bruno = json.loads((args.output / "bruno-results.json").read_text(encoding="utf-8"))
        assert len(bruno) == 1 and len(bruno[0]["results"]) == request_count
        assert bruno[0]["summary"]["passedRequests"] == request_count
        for execution in bruno[0]["results"]:
            assert execution["error"] is None and execution["response"]["status"] == 200
        assert len(postman["run"]["executions"]) == request_count
        assert not postman["run"]["failures"]
        for execution in postman["run"]["executions"]:
            assert execution["response"]["code"] == 200
            if execution["request"]["url"]["path"][-1] == "documents":
                data = bytes(execution["response"]["stream"]["data"])
                assert data.startswith(b"PK") if execution["item"]["name"].endswith("Editable Word output") else data.startswith(b"%PDF-")
        runtime.clean()
        print(f"Both clients: {request_count} requests including all {scenario_count} document scenarios PASS", flush=True)
    finally:
        runtime.close()


if __name__ == "__main__":
    main()
