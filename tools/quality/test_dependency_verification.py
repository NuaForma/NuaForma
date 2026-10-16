import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import dependency_verification as verification


class DependencyVerificationTest(unittest.TestCase):
    def test_fresh_bytes_must_match_and_come_from_an_official_repository(self):
        key = ("example", "fixture", "1.0", "fixture-1.0.jar")
        expected = hashlib.sha256(b"reviewed").hexdigest()
        for content, url, succeeds in (
                (b"reviewed", verification.REPOSITORIES[0] + verification.relative_url(key), True),
                (b"modified", verification.REPOSITORIES[0] + verification.relative_url(key), False),
                (b"reviewed", "https://example.invalid/file", False)):
            response = io.BytesIO(content)
            response.url = url
            with patch.object(verification.urllib.request, "urlopen", return_value=response):
                if succeeds:
                    self.assertEqual(verification.review_artifact((key, expected))["sha256"], expected)
                else:
                    with self.assertRaises(ValueError):
                        verification.review_artifact((key, expected))

    def test_repository_inventory_is_fully_reviewed(self):
        self.assertGreater(verification.validate(), 0)

    def test_invalid_or_exempted_metadata_is_rejected(self):
        original = (verification.ROOT / verification.METADATA).read_text()
        mutations = (
            original.replace("<verify-metadata>true", "<verify-metadata>false"),
            original.replace("<components>", "<components/><!--", 1) + "-->",
            original.replace("</configuration>", '<trusted-artifacts><trust group=".*" regex="true"/></trusted-artifacts></configuration>'),
            original.replace("<sha256 ", "<sha1 ", 1),
            original.replace("<sha256 ", '<ignored-keys/><sha256 ', 1),
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "metadata.xml"
            for content in mutations:
                with self.subTest(content=content[:50]):
                    path.write_text(content)
                    with self.assertRaises((ValueError, ET.ParseError)):
                        verification.inventory(path)

    def test_review_cannot_omit_change_duplicate_or_redirect_an_artifact(self):
        receipts = json.loads((verification.ROOT / verification.RECEIPTS).read_text())
        changes = []
        omitted = copy.deepcopy(receipts)
        omitted["artifacts"].pop()
        changes.append(omitted)
        changed = copy.deepcopy(receipts)
        changed["artifacts"][0]["sha256"] = "0" * 64
        changes.append(changed)
        duplicate = copy.deepcopy(receipts)
        duplicate["artifacts"].append(duplicate["artifacts"][0])
        changes.append(duplicate)
        redirected = copy.deepcopy(receipts)
        redirected["artifacts"][0]["repositoryUrl"] = "https://example.invalid/artifact"
        changes.append(redirected)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle").mkdir()
            (root / verification.METADATA).write_bytes((verification.ROOT / verification.METADATA).read_bytes())
            (root / "gradle.properties").write_text("org.gradle.dependency.verification=strict\n")
            for content in changes:
                (root / verification.RECEIPTS).write_text(json.dumps(content))
                with self.assertRaises(ValueError):
                    verification.validate(root)
            (root / verification.RECEIPTS).write_text(json.dumps(receipts))
            (root / "gradle.properties").write_text("org.gradle.dependency.verification=lenient\n")
            with self.assertRaisesRegex(ValueError, "Strict"):
                verification.validate(root)


if __name__ == "__main__":
    unittest.main()
