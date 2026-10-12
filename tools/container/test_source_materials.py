import hashlib
import json
import pathlib
import tempfile
import unittest

from package_source_materials import add_record
from stage_application_materials import stage


class SourceMaterialsTest(unittest.TestCase):
    def test_source_archive_rejects_traversal_and_changed_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "source.txt"
            source.write_bytes(b"synthetic source")
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            entries = {}
            add_record(entries, root, "source.txt", digest, "upstream")
            self.assertEqual({"upstream/source.txt"}, set(entries))
            for path, expected in (("../source.txt", digest), ("source.txt", "0" * 64)):
                with self.assertRaises(AssertionError): add_record({}, root, path, expected, "upstream")

    def test_notice_staging_requires_complete_classpath_and_original_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            application, collection, provenance = [root / name for name in ("app", "collection", "provenance")]
            (application / "lib").mkdir(parents=True)
            collection.mkdir()
            provenance.mkdir()
            jar = application / "lib/example-1.jar"
            jar.write_bytes(b"synthetic JAR bytes")
            record = dict(components=[dict(group="example", version="1", file=jar.name,
                                         sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), materials=[])], errors=[])
            (collection / "inventory.json").write_text(json.dumps(record))
            (provenance / "inventory.json").write_text(json.dumps(dict(files=[])))
            stage(collection, provenance, application, "0.1.0-alpha.1")
            jar.write_bytes(b"modified bytes")
            with self.assertRaises(AssertionError): stage(collection, provenance, application, "0.1.0-alpha.1")
            extra = application / "lib/uninventoried.jar"
            extra.write_bytes(b"synthetic unlisted JAR")
            with self.assertRaises(AssertionError): stage(collection, provenance, application, "0.1.0-alpha.1")


if __name__ == "__main__":
    unittest.main()
