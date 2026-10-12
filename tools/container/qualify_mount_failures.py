"""Real-startup mount failures and technical font-permission checks; generated QA inputs only."""
import argparse
import json
import pathlib
import struct
import time
from qualify_service import Runtime, http, generate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--seccomp", type=pathlib.Path, default=pathlib.Path("deployment/pdf-runtime/seccomp.json"))
    parser.add_argument("--fixtures", type=pathlib.Path, required=True)
    parser.add_argument("--fonts", type=pathlib.Path, required=True)
    parser.add_argument("--incompatible", type=pathlib.Path, required=True)
    parser.add_argument("--missing-dependency", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    checks = []
    def caps(runtime):
        status, _, body = http(runtime.connect(), "/api/v1/capabilities")
        assert status == 200
        return json.loads(body)
    def mount(source, target):
        return ("--mount", "type=bind,source=" + str(source.resolve()) + ",target=" + target + ",readonly")
    for source, expected in ((args.incompatible, "INCOMPATIBLE_CONTRACT"), (args.missing_dependency, "INVALID_PROVIDER")):
        runtime = Runtime(args, *mount(source, "/opt/nuaforma/extensions"))
        try:
            catalog = caps(runtime)
            assert not any(provider["id"] == "mounted-example" for provider in catalog["providers"])
            assert any(item["code"] == expected for item in catalog["diagnostics"]), catalog
            if expected == "INCOMPATIBLE_CONTRACT":
                assert "SYNTHETIC_EXTENSION_INITIALIZED" not in runtime.command("logs", runtime.name)
            runtime.clean()
            checks.append(expected)
        finally:
            runtime.close()
    malformed = args.output / "malformed"
    malformed.mkdir()
    (malformed / "bad.jar").write_bytes(b"not-a-jar")
    runtime = Runtime(args, *mount(malformed, "/opt/nuaforma/extensions"))
    try:
        deadline = time.monotonic() + 20
        state = None
        while time.monotonic() < deadline:
            state = json.loads(runtime.command("inspect", runtime.name))[0]["State"]
            if not state["Running"]:
                break
            time.sleep(0.2)
        assert state and not state["Running"] and state["ExitCode"] != 0
        assert "INVALID_EXTENSION_JAR" in runtime.command("logs", runtime.name)
        checks.append("MALFORMED_EXTENSION_FAILS_STARTUP")
    finally:
        runtime.close()
    invalid = args.output / "invalid-font"
    invalid.mkdir()
    (invalid / "bad.ttf").write_bytes(b"not-a-font")
    runtime = Runtime(args, *mount(invalid, "/opt/nuaforma/custom-fonts"))
    try:
        catalog = caps(runtime)
        assert catalog["diagnostics"] and not any(provider["id"] == "nuaforma-docx" for provider in catalog["providers"])
        assert generate(runtime.base, "docx", (args.fixtures / "font-reference/template.docx").read_bytes(), {"reference": "BAD", "customer": {"name": "Ada"}})[0] >= 400
        checks.append("INVALID_FONT_DISCOVERY_REJECTED")
    finally:
        runtime.close()
    restricted = args.output / "restricted-fonts"
    restricted.mkdir()
    for source in args.fonts.glob("*.ttf"):
        data = bytearray(source.read_bytes())
        if source.name == "LibreBarcode39-Regular.ttf":
            count = struct.unpack_from(">H", data, 4)[0]
            offset = next(struct.unpack_from(">I", data, 12 + index * 16 + 8)[0] for index in range(count)
                          if data[12 + index * 16:16 + index * 16] == b"OS/2")
            struct.pack_into(">H", data, offset + 8, 2)
        (restricted / source.name).write_bytes(data)
    runtime = Runtime(args, *mount(restricted, "/opt/nuaforma/custom-fonts"))
    try:
        catalog = caps(runtime)
        assert any(provider["fonts"]["status"] == "AVAILABLE" for provider in catalog["providers"])
        status, _, body = generate(runtime.base, "pdf", (args.fixtures / "font-reference/template.docx").read_bytes(),
                                   {"customer": {"name": "Ada"}, "reference": "RESTRICTED"})
        assert status == 422 and b"INVALID_REQUEST" in body, (status, body[:1024])
        runtime.clean()
        checks.append("MOUNTED_FONT_EMBEDDING_RESTRICTION_ENFORCED")
    finally:
        runtime.close()
    (args.output / "qualification.json").write_text(json.dumps(checks, indent=2), encoding="utf-8")
    print(json.dumps(checks))


if __name__ == "__main__":
    main()
