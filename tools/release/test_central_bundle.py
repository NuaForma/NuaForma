import hashlib
import pathlib
import unittest
import zipfile
from unittest.mock import patch

from prepare_central_bundle import prepare, release_version
import test_release_tools as fixtures
from verify_libraries import MODULES, artifacts


class CentralBundleTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.PublicationTest("test_complete_fixture_passes")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.repository = fixture.repository
        self.output = self.repository / "bundle.zip"
        for module in MODULES:
            for file in artifacts(self.repository, module, "0.1.0"):
                pathlib.Path(str(file) + ".asc").write_text("synthetic signature fixture, not a valid signature")

    def test_explicit_release_versions_only(self):
        for value in ("0.1.0", "0.1.0-alpha.1", "1.0.0"):
            self.assertEqual(value, release_version(value))
        for value in ("0.1", "0.1.0-SNAPSHOT", "../0.1.0", "01.1.0", "0.1.0-alpha.01"):
            with self.subTest(value=value), self.assertRaises(AssertionError): release_version(value)

    @patch("prepare_central_bundle.verify_all", return_value=[])
    def test_bundle_allowlists_files_is_deterministic_and_never_overwrites(self, verification):
        # Packaging unit test only; real public-key verification is a separate integration check.
        (self.repository / "private.txt").write_text("synthetic secret exclusion canary")
        record = prepare(self.repository, "0.1.0", "A" * 40, self.repository / "public.asc", self.output)
        verification.assert_called_once()
        self.assertFalse(record["uploaded"])
        with zipfile.ZipFile(self.output) as bundle:
            self.assertEqual(120, len(bundle.namelist()))
            self.assertTrue(all(name.startswith("dev/nuaforma/") for name in bundle.namelist()))
            self.assertFalse(any("private" in name or "metadata" in name for name in bundle.namelist()))
        duplicate = self.repository / "second.zip"
        prepare(self.repository, "0.1.0", "A" * 40, self.repository / "public.asc", duplicate)
        self.assertEqual(hashlib.sha256(self.output.read_bytes()).digest(), hashlib.sha256(duplicate.read_bytes()).digest())
        with self.assertRaises(AssertionError):
            prepare(self.repository, "0.1.0", "A" * 40, self.repository / "public.asc", self.output)

    @patch("prepare_central_bundle.verify_all", side_effect=AssertionError("Signature failed"))
    def test_failed_signatures_prevent_bundle_creation(self, verification):
        with self.assertRaises(AssertionError):
            prepare(self.repository, "0.1.0", "A" * 40, self.repository / "public.asc", self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__": unittest.main()
