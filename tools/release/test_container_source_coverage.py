import hashlib
import json
import pathlib
import tempfile
import unittest
from check_container_source_coverage import verify_inventory


class SourceCoverageTest(unittest.TestCase):
    def test_container_boundary_requires_its_exact_package_scope_and_retains_foreign_jar_checks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / "image.json").write_text(json.dumps({"Labels": {"dev.nuaforma.validation-boundary": "container"}}))
            data = b"synthetic\t1\tsynthetic\t1\n"
            (root / "outer-packages.tsv").write_bytes(data)
            record = dict(packages={"outer-packages.tsv": hashlib.sha256(data).hexdigest()}, foreignJars={})
            for name in ("application-sha256.txt", "validator-sha256.txt", "verapdf-sha256.txt"):
                (root / name).write_text("")
            verify_inventory(root, record)
            record["packages"]["renderer-packages.tsv"] = hashlib.sha256(data).hexdigest()
            with self.assertRaises(ValueError): verify_inventory(root, record)
            del record["packages"]["renderer-packages.tsv"]
            (root / "verapdf-sha256.txt").write_text("c" * 64 + "  /lib/changed.jar\n")
            with self.assertRaises(ValueError): verify_inventory(root, record)

    def test_split_jvm_layout_cannot_reuse_historical_source_review(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / "image.json").write_text(json.dumps({"Labels": {"dev.nuaforma.jvm-layout": "server25-validator17"}}))
            record = dict(packages={}, foreignJars={})
            with self.assertRaisesRegex(ValueError, "Both Java runtime"):
                verify_inventory(root, record)
            data = b"synthetic-validator17-and-server25-runtime-checksums"
            (root / "java-sha256.txt").write_bytes(data)
            with self.assertRaisesRegex(ValueError, "Both Java runtime"):
                verify_inventory(root, record)
            record["javaRuntimeFilesSha256"] = hashlib.sha256(data).hexdigest()
            for name in ("outer-packages.tsv", "renderer-packages.tsv"):
                (root / name).write_bytes(b"synthetic")
                record["packages"][name] = hashlib.sha256(b"synthetic").hexdigest()
            for name in ("application-sha256.txt", "validator-sha256.txt", "verapdf-sha256.txt"):
                (root / name).write_text("")
            verify_inventory(root, record)
            (root / "java-sha256.txt").write_bytes(data + b"changed")
            with self.assertRaisesRegex(ValueError, "Both Java runtime"):
                verify_inventory(root, record)

    def test_changed_packages_and_foreign_jars_cannot_reuse_a_source_review(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            record = dict(packages={}, foreignJars={})
            for name in ("outer-packages.tsv", "renderer-packages.tsv"):
                data = b"synthetic\t1\tsynthetic\t1\n"
                (root / name).write_bytes(data)
                record["packages"][name] = hashlib.sha256(data).hexdigest()
            for name in ("application-sha256.txt", "validator-sha256.txt", "verapdf-sha256.txt"):
                (root / name).write_text("a" * 64 + "  /lib/third-party-1.jar\n" + "b" * 64 + "  /lib/nuaforma-core-0.1.0-alpha.1.jar\n")
                record["foreignJars"][name + "/third-party-1.jar"] = "a" * 64
            verify_inventory(root, record)
            (root / "outer-packages.tsv").write_bytes(b"changed version")
            with self.assertRaises(ValueError): verify_inventory(root, record)
            (root / "outer-packages.tsv").write_bytes(data)
            (root / "verapdf-sha256.txt").write_text("c" * 64 + "  /lib/third-party-1.jar\n")
            with self.assertRaises(ValueError): verify_inventory(root, record)
            with self.assertRaises(ValueError): verify_inventory(root, dict(packages={}, foreignJars={}))


if __name__ == "__main__":
    unittest.main()
