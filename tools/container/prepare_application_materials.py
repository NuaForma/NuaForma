"""Prepare original dependency material after installDist and before a service image build."""
import argparse
import pathlib
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--application", type=pathlib.Path, default=pathlib.Path("nuaforma-server/build/install/nuaforma-server"))
    parser.add_argument("--cache", type=pathlib.Path, default=pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    sys.path.insert(0, str(root / "tools/release"))
    from verify_libraries import project_version
    version = project_version()
    tools = pathlib.Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory(prefix="nuaforma-dependency-materials-") as temporary:
        directory = pathlib.Path(temporary)
        collection, provenance = directory / "collection", directory / "provenance"
        subprocess.run([sys.executable, str(tools / "collect_application_materials.py"),
                        "--application", str(args.application / "lib"), "--cache", str(args.cache),
                        "--version", version, "--output", str(collection)], check=True)
        subprocess.run([sys.executable, str(tools / "collect_build_provenance.py"), "--application-only",
                        "--output", str(provenance)], check=True)
        from stage_application_materials import stage
        stage(collection, provenance, args.application, version)


if __name__ == "__main__":
    main()
