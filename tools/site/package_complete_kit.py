"""Package existing downloadable sources without nested deployment ZIPs."""
from build_client_collections import OUT, archive_files


def package():
    files = {"START-HERE.md": OUT / "START-HERE.md"}
    for prefix, folder in (("Deploy/AMD64", "docker"), ("Deploy/ARM64", "docker-arm64"), ("Deploy/Helm", "helm"), ("Collections/Bruno", "bruno"), ("Collections/Postman", "postman")):
        for path in sorted((OUT / folder).rglob("*")):
            if path.is_file():
                assert not path.is_symlink()
                files[prefix + "/" + path.relative_to(OUT / folder).as_posix()] = path
    for path in [OUT / "EXAMPLES.md", OUT / "scenarios.json", OUT / "nuaforma-example-template.docx", *sorted(OUT.glob("[0-9][0-9]-*.json"))]:
        files["Testkit/" + path.name] = path
    archive_files(OUT / "nuaforma-complete-kit.zip", files)


if __name__ == "__main__":
    package()
    print("Packaged complete deployment, collections and test kit")
