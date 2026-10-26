import io
import hashlib
import json
import pathlib
import tarfile
import tempfile
import unittest

from review_package_materials import read_member, review


class NoticeTest(unittest.TestCase):
    def archive(self, links):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            item = tarfile.TarInfo("doc/gcc-base/copyright")
            content = b"License: GPL-3+ with GCC runtime exception\n"
            item.size = len(content)
            archive.addfile(item, io.BytesIO(content))
            for name, target in links:
                item = tarfile.TarInfo(name)
                item.type = tarfile.SYMTYPE
                item.linkname = target
                archive.addfile(item)
        stream.seek(0)
        return tarfile.open(fileobj=stream)

    def test_package_directory_links_resolve_inside_archive(self):
        with self.archive([("doc/libgcc", "gcc-base")]) as archive:
            self.assertIn(b"runtime exception", read_member(archive, "doc/libgcc/copyright"))

    def test_cycle_and_escape_links_fail(self):
        for links in ([('doc/a', 'a')], [('doc/a', '../../outside')], [('doc/a', '/etc')]):
            with self.archive(links) as archive, self.assertRaises(ValueError):
                read_member(archive, "doc/a/copyright")


class NativeReviewTest(unittest.TestCase):
    def fixture(self, root, boundary="container"):
        inventory, notices, sources = [root / name for name in ("inventory", "notices", "sources")]
        for folder in (inventory, notices, sources):
            folder.mkdir()
        (inventory / "image.json").write_text(json.dumps(dict(Id="candidate", Labels={"dev.nuaforma.validation-boundary": boundary})))
        (inventory / "outer-packages.tsv").write_text("example\t1\texample-source\t1\n")
        source = sources / "example.tar"
        source.write_bytes(b"retained source")
        source_record = dict(source="example-source", version="1", descriptorChecksumsVerified=True,
                             files=[dict(path=source.name, sha256=hashlib.sha256(source.read_bytes()).hexdigest())])
        (sources / "collection.json").write_text(json.dumps(dict(errors=[], upstream=[], debian=[source_record])))
        archive_file = notices / "outer-doc.tar"
        with tarfile.open(archive_file, "w") as archive:
            content = b"License: Apache-2.0\n"
            item = tarfile.TarInfo("doc/example/copyright")
            item.size = len(content)
            archive.addfile(item, io.BytesIO(content))
        (notices / "inventory.json").write_text(json.dumps(dict(image="candidate", files=[dict(path=archive_file.name, sha256=hashlib.sha256(archive_file.read_bytes()).hexdigest())])))
        return inventory, notices, sources

    def test_native_review_needs_no_retired_renderer_and_does_not_approve(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            result = review(*self.fixture(root), root / "output")
            self.assertEqual([], result["gaps"])
            self.assertEqual(["example.tar"], result["packages"][0]["sourceFiles"])
            self.assertEqual(["Apache-2.0"], result["packages"][0]["declaredLicenseLabels"])
            self.assertFalse(result["auditApproved"])
            self.assertNotIn("LibreOffice", str(result["pending"]))

    def test_older_boundary_requires_matching_historical_tools(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            with self.assertRaisesRegex(AssertionError, "older image boundaries"):
                review(*self.fixture(root, "bubblewrap"), root / "output")
            self.assertFalse((root / "output").exists())

    def test_tampered_notice_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            inputs = self.fixture(root)
            (inputs[1] / "outer-doc.tar").write_bytes(b"changed")
            with self.assertRaises(AssertionError):
                review(*inputs, root / "output")
            self.assertFalse((root / "output").exists())


if __name__ == "__main__":
    unittest.main()
