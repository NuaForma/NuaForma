import hashlib
import pathlib
import tempfile
import unittest
from check_container_source_coverage import verify_inventory


class SourceCoverageTest(unittest.TestCase):
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
