import hashlib
import json
import pathlib
import tempfile
from types import SimpleNamespace
import unittest

from package_source_materials import add_record, package
from stage_application_materials import stage


class SourceMaterialsTest(unittest.TestCase):
    def test_notice_staging_accepts_new_version_without_accepting_unsafe_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            application, collection, provenance = [root / name for name in ("app", "collection", "provenance")]
            (application / "lib").mkdir(parents=True)
            collection.mkdir()
            provenance.mkdir()
            (collection / "inventory.json").write_text(json.dumps(dict(components=[], errors=[])))
            (provenance / "inventory.json").write_text(json.dumps(dict(files=[])))
            stage(collection, provenance, application, "0.2.0-alpha.1")
            self.assertEqual("0.2.0-alpha.1", json.loads((application / "third-party-materials/inventory.json").read_text())["version"])
            for version in ("../escape", "0.2.0/alpha.1", "0.2.0-alpha.1;exit", "02.0.0"):
                with self.assertRaises(AssertionError):
                    stage(collection, provenance, application, version)

    def test_versioned_source_packaging_accepts_new_alpha_and_rejects_unsafe_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            folders = {name: root / name for name in ("sources", "provenance", "application", "validator")}
            for folder in folders.values(): folder.mkdir()
            (folders["sources"] / "collection.json").write_text(json.dumps(dict(errors=[], debian=[], upstream=[])))
            (folders["provenance"] / "inventory.json").write_text(json.dumps(dict(files=[])))
            (folders["application"] / "inventory.json").write_text(json.dumps(dict(retained=[])))
            output = root / "output"
            package(SimpleNamespace(**folders, version="0.1.0-alpha.2", output=output))
            self.assertEqual({f"nuaforma-sources-{group}-0.1.0-alpha.2.tar" for group in ("debian", "java", "jvm")}, {file.name for file in output.glob("*.tar")})
            for version in ("../escape", "0.1.0/alpha.2", "0.1.0-alpha.2;exit", "01.1.0"):
                with self.assertRaises(AssertionError):
                    package(SimpleNamespace(**folders, version=version, output=root / "unsafe"))
            self.assertFalse((root / "unsafe").exists())

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

    def test_supplemental_upstream_notices_are_retained_outside_classpath(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            application, collection, provenance = [root / name for name in ("app", "collection", "provenance")]
            (application / "lib").mkdir(parents=True)
            collection.mkdir()
            provenance.mkdir()
            (collection / "inventory.json").write_text(json.dumps(dict(components=[], errors=[])))
            names = ("xalan-11.0.0-LICENSE.txt", "xalan-11.0.0-NOTICE.txt", "mbassador-1.3.2-LICENSE")
            records = []
            for name in names:
                data = ("synthetic original notice: " + name).encode()
                (provenance / name).write_bytes(data)
                records.append(dict(file=name, sha256=hashlib.sha256(data).hexdigest()))
            (provenance / "inventory.json").write_text(json.dumps(dict(files=records)))
            stage(collection, provenance, application, "0.1.0-alpha.1")
            for name in names:
                self.assertEqual((provenance / name).read_bytes(), (application / "third-party-materials" / name).read_bytes())
            self.assertFalse(list((application / "lib").iterdir()))
            (provenance / names[0]).write_bytes(b"tampered notice")
            with self.assertRaises(AssertionError): stage(collection, provenance, application, "0.1.0-alpha.1")


if __name__ == "__main__":
    unittest.main()
