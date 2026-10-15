"""Keep newer bytecode out of the effective Java 17 library dependency graph."""
import pathlib
import struct
import tempfile
import unittest
import zipfile
from verify_libraries import verify_java17_classes


class JavaBaselineTest(unittest.TestCase):
    def jar(self, directory, entries, multi=True):
        path = pathlib.Path(directory) / "synthetic.jar"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMulti-Release: " + str(multi).lower() + "\n")
            for name, (major, minor) in entries.items():
                archive.writestr(name, b"\xca\xfe\xba\xbe" + struct.pack(">HH", minor, major))
        return path

    def test_optional_newer_multi_release_branch_is_not_java17_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            verify_java17_classes(self.jar(directory, {"Example.class": (61, 0),
                "META-INF/versions/17/Example.class": (61, 0), "META-INF/versions/25/Example.class": (69, 0)}))

    def test_incompatible_base_effective_override_and_preview_are_rejected(self):
        for name, version in (("Example.class", (69, 0)), ("META-INF/versions/17/Example.class", (69, 0)),
                              ("Example.class", (61, 65535))):
            with self.subTest(name=name, version=version), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(AssertionError):
                    verify_java17_classes(self.jar(directory, {name: version}))

    def test_only_the_highest_applicable_multi_release_class_is_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            verify_java17_classes(self.jar(directory, {"Example.class": (69, 0),
                "META-INF/versions/17/Example.class": (61, 0)}))


if __name__ == "__main__":
    unittest.main()
