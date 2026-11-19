"""Retain upstream build scripts and missing notices for the reviewed runtime versions."""
import argparse
import hashlib
import json
import pathlib
import tarfile
import urllib.request


RUNTIMES = {
    "x86_64": ("8d9fd6fa96e5066571a13823b07c2934e5958d8b", ("17.0.21.1+2", "41b754a57540"), ("25.0.5.1+2-LTS", "1513e3f76ff3")),
    "aarch64": ("1cf43d7a3e77886bfea7d550a902e151a87d724d", ("17.0.21+9", "551f220d8884"), ("25.0.5+8-LTS", "7ec19fb1eda7")),
}


def runtime_identity(path):
    with tarfile.open(path) as archive:
        member = next(member for member in archive if member.isfile())
        assert member.size <= 65_536, "Oversized Java release metadata"
        release = archive.extractfile(member).read().decode()
    return dict(line.split("=", 1) for line in release.splitlines() if "=" in line)


def check_runtime(identity, architecture, build_source, runtime):
    version, source = runtime
    assert identity["OS_ARCH"] == f'"{architecture}"'
    assert identity["BUILD_SOURCE"] == f'"git:{build_source}"'
    assert identity["SOURCE"] == f'".:git:{source}"'
    assert identity["JAVA_RUNTIME_VERSION"] == f'"{version}"'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notices", type=pathlib.Path)
    parser.add_argument("--application-only", action="store_true")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if not args.application_only:
        assert args.notices is not None
        validator = runtime_identity(args.notices / "java-release.tar")
        architecture = validator["OS_ARCH"].strip('"')
        build_source, validator_runtime, server_runtime = RUNTIMES[architecture]
        check_runtime(validator, architecture, build_source, validator_runtime)
        server_release = args.notices / "server-java-release.tar"
        if server_release.is_file():
            check_runtime(runtime_identity(server_release), architecture, build_source, server_runtime)
    args.output.mkdir(parents=True, exist_ok=False)
    urls = {
        "temurin-build-8d9fd6fa96e5066571a13823b07c2934e5958d8b.tar.gz": "https://api.github.com/repos/adoptium/temurin-build/tarball/8d9fd6fa96e5066571a13823b07c2934e5958d8b",
        "temurin-build-1cf43d7a3e77886bfea7d550a902e151a87d724d.tar.gz": "https://api.github.com/repos/adoptium/temurin-build/tarball/1cf43d7a3e77886bfea7d550a902e151a87d724d",
        "temurin-build-containers-86e89ce20ba391b588e243844d0c0d996071b3ed.tar.gz": "https://api.github.com/repos/adoptium/containers/tarball/86e89ce20ba391b588e243844d0c0d996071b3ed",
        "docx4j-17.3.4-LICENSE": "https://raw.githubusercontent.com/plutext/docx4j/VERSION_17_3_4/legals/LICENSE",
        "docx4j-17.3.4-NOTICE": "https://raw.githubusercontent.com/plutext/docx4j/VERSION_17_3_4/legals/NOTICE",
        "antlr-2.7.7.tar.gz": "https://www.antlr2.org/download/antlr-2.7.7.tar.gz",
        "antlr-2-license.html": "https://www.antlr2.org/license.html",
        "antlr-2.7.7-copyright": "https://sources.debian.org/data/main/a/antlr/2.7.7%2Bdfsg-6/debian/copyright",
        "xalan-11.0.0-LICENSE.txt": "https://raw.githubusercontent.com/plutext/xalan-j/xalan-parent-11.0.0/LICENSE.txt",
        "xalan-11.0.0-NOTICE.txt": "https://raw.githubusercontent.com/plutext/xalan-j/xalan-parent-11.0.0/NOTICE.txt",
        "mbassador-1.3.2-LICENSE": "https://raw.githubusercontent.com/bennidi/mbassador/1e98ff489d2b2cbc78717a6f543a16fbaa1a8118/LICENSE",
    }
    records = []
    expected = {
        "docx4j-17.3.4-LICENSE": "8376f47fa872ae6142a7c08f66f13a7ab32ad2c4554e118a3e462c8c8ee68468",
        "docx4j-17.3.4-NOTICE": "6ebab83ef4ba80b201729e252bb19a4c8ef20bba582aaddc6d5c112a2aeb6c96",
        "antlr-2.7.7.tar.gz": "853aeb021aef7586bda29e74a6b03006bcb565a755c86b66032d8ec31b67dbb9",
        "antlr-2-license.html": "72d8ba2e86ccc57b65d83f0af10ae0cade32f5305eb2b95b4aed71e1eb891752",
        "antlr-2.7.7-copyright": "bc290c0e5bc791362aeb044a137e135a008f6f60bb72ed443d3aa36627df3aa3",
        "xalan-11.0.0-LICENSE.txt": "f90dc44684b75fa0cdfe5b2cdc33a50fb8029c6514fa78571b06f8b2fcddf903",
        "xalan-11.0.0-NOTICE.txt": "6c0cbf85b99b3d4ad312e8d3d0dc9996ff6eea5eed576d31361a60b7f99c4249",
        "mbassador-1.3.2-LICENSE": "ab2654d8c44f6816b82afa9c45bd79a5b203b7b08634a67a95d02b26aa9a7fec",
    }
    for name, url in urls.items():
        if args.application_only and name not in expected:
            continue
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "NuaForma-release-source-review"}), timeout=60) as response:
            assert response.url.startswith("https://")
            data = response.read(128 * 1024 * 1024 + 1)
        assert data and len(data) <= 128 * 1024 * 1024
        if name in expected:
            assert hashlib.sha256(data).hexdigest() == expected[name], "Upstream legal material changed; review required"
        (args.output / name).write_bytes(data)
        records.append(dict(file=name, url=url, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
        print("Retained", name, flush=True)
    (args.output / "inventory.json").write_text(json.dumps(dict(files=records, auditApproved=False), indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
