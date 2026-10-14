"""Package explicit deployment files with unchanged reviewed profiles and their notices."""
import pathlib
import shutil
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOWNLOADS = ROOT / "site/downloads"

for name in ("docker", "docker-arm64", "helm"):
    folder = DOWNLOADS / name
    for source, target in (("seccomp.json", "seccomp.json"), ("NOTICE", "NOTICE"), ("licenses/APACHE-2.0.txt", "LICENSE"), ("nuaforma.apparmor", "nuaforma.apparmor")):
        shutil.copyfile(ROOT / "deployment/pdf-runtime" / source, folder / target)
    if name.startswith("docker"):
        shutil.copyfile(ROOT / "deployment/pdf-runtime/compose.apparmor.yaml", folder / "compose.apparmor.yaml")
    with zipfile.ZipFile(DOWNLOADS / (name + "-kit.zip"), "w", zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(folder.rglob("*")):
            if file.is_file():
                assert not file.is_symlink()
                info = zipfile.ZipInfo(file.relative_to(folder).as_posix(), (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, file.read_bytes())
    print("Packaged", name, "deployment kit")
