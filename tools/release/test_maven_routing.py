import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch

from central_portal import upload
from central_publish import publish
from maven_destination import destination
from publish_candidate import publish_libraries


class CandidateRoutingTest(unittest.TestCase):
    def test_central_entry_points_reject_every_development_version_before_network_or_files(self):
        for version in ("1.2.3-alpha.1", "1.2.3-beta.1", "1.2.3-rc.1", "1.2.3-SNAPSHOT"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                upload(None, version, "", "", opener=lambda *a, **k: self.fail("Unexpected Central upload"))
            with self.subTest(version=version), self.assertRaises(ValueError):
                publish("12345678-1234-1234-1234-123456789abc", {"version": version}, "",
                        opener=lambda *a, **k: self.fail("Unexpected Central publication"))

    def test_prerelease_uses_only_nexus_credentials_and_exact_approved_bundle(self):
        record = {"version": "1.2.3-alpha.1", "libraryHashes": {"approved": "hash"}, "bundleSha256": "reviewed-zip-sha256"}
        record["mavenPublication"] = destination(record["version"])
        with patch.dict(os.environ, {"NEXUS_USERNAME": "synthetic", "NEXUS_PASSWORD": "disposable"}, clear=True), \
             patch("publish_candidate.publish_bundle") as nexus, patch("publish_candidate.upload") as central:
            publish_libraries(pathlib.Path("candidate"), record, pathlib.Path("receipts"))
            central.assert_not_called()
            self.assertEqual(pathlib.Path("candidate/jvm/central.zip"), nexus.call_args.args[0])
            self.assertIs(record["libraryHashes"], nexus.call_args.args[3])
            self.assertEqual(record["bundleSha256"], nexus.call_args.args[4])

    def test_stable_uses_central_staging_validation_publication_and_no_nexus(self):
        with tempfile.TemporaryDirectory() as directory:
            record = {"version": "1.2.3", "bundleSha256": "reviewed", "mavenPublication": destination("1.2.3")}
            with patch.dict(os.environ, {"MAVEN_CENTRAL_USERNAME": "synthetic", "MAVEN_CENTRAL_PASSWORD": "disposable"}, clear=True), \
                 patch("publish_candidate.publish_bundle") as nexus, \
                 patch("publish_candidate.upload", return_value={"deploymentId": "reviewed-deployment"}) as staged, \
                 patch("publish_candidate.validated") as validated, patch("publish_candidate.publish", return_value={"publishRequested": True}) as published:
                publish_libraries(pathlib.Path(directory), record, pathlib.Path(directory))
                nexus.assert_not_called()
                self.assertEqual("1.2.3", staged.call_args.args[1])
                self.assertEqual("reviewed-deployment", validated.call_args.args[0])
                self.assertIs(record, published.call_args.args[1])

    def test_changed_reviewed_destination_and_snapshot_cannot_be_published_as_release(self):
        for version in ("1.2.3-alpha.1", "1.2.3-SNAPSHOT"):
            with self.assertRaises(ValueError), patch("publish_candidate.publish_bundle") as nexus, patch("publish_candidate.upload") as central:
                publish_libraries(None, {"version": version, "mavenPublication": destination("1.2.3")}, None)
            nexus.assert_not_called()
            central.assert_not_called()


if __name__ == "__main__":
    unittest.main()
