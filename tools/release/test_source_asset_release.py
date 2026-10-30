import copy
import hashlib
import io
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch

import release_candidate as candidate


class SourceAssetReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        (self.root / "release-materials").mkdir()
        self.version = "0.3.0-alpha.5"
        self.payload = b"synthetic corresponding-source archive"
        self.assets = [{"url": f"https://github.com/NuaForma/NuaForma/releases/download/sources-v{self.version}/nuaforma-sources-{group}-{self.version}.tar",
                        "sha256": hashlib.sha256(self.payload).hexdigest()} for group in ("debian", "java", "jvm")]
        self.records = {app: {"version": self.version, "application": app, "assetTag": "sources-v" + self.version,
                              "sourceMaterials": copy.deepcopy(self.assets)} for app in candidate.APPLICATIONS}
        self.save()
        self.patch = patch.object(candidate, "ROOT", self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def save(self):
        for app, data in self.records.items():
            suffix = "-playground" if app == "playground" else ""
            (self.root / "release-materials" / (self.version + suffix + ".json")).write_text(json.dumps(data))

    def response(self, url, timeout):
        result = io.BytesIO(self.payload)
        result.url = url
        return result

    def test_sources_only_tag_allows_all_three_exact_public_archives(self):
        with patch.object(candidate.urllib.request, "urlopen", side_effect=self.response) as download:
            assets = candidate.sources(self.version)
        self.assertEqual(3, download.call_count)
        self.assertEqual(sorted(self.assets, key=lambda a: a["url"]), assets)
        self.assertEqual("sources-v" + self.version, candidate.source_asset_tag(self.version))

    def test_historical_source_asset_tags_keep_their_existing_immutable_urls(self):
        for record in self.records.values():
            del record["assetTag"]
            for asset in record["sourceMaterials"]:
                asset["url"] = asset["url"].replace("/sources-v", "/v")
        self.save()
        with patch.object(candidate.urllib.request, "urlopen", side_effect=self.response):
            self.assertEqual(3, len(candidate.sources(self.version)))
        self.assertEqual("v" + self.version, candidate.source_asset_tag(self.version))

    def test_redirected_tag_mismatched_application_or_version_is_rejected(self):
        original = copy.deepcopy(self.records)
        for field, value in (("assetTag", "../../other"), ("application", "playground"), ("version", "0.3.0"),
                             ("assetTag", "v" + self.version)):
            self.records = copy.deepcopy(original)
            self.records["server"][field] = value
            self.save()
            with self.subTest(field=field), self.assertRaises(ValueError):
                candidate.source_asset_tag(self.version)

    def test_wrong_archive_group_missing_source_and_changed_public_bytes_are_rejected(self):
        original = copy.deepcopy(self.records)
        for changed in (self.assets[:2], [dict(a, url=a["url"].replace("-debian-", "-unknown-")) for a in self.assets]):
            self.records = copy.deepcopy(original)
            for record in self.records.values():
                record["sourceMaterials"] = changed
            self.save()
            with self.assertRaises(ValueError), patch.object(candidate.urllib.request, "urlopen") as request:
                candidate.sources(self.version)
            request.assert_not_called()
        self.records = original
        self.save()
        self.payload = b"changed source bytes"
        with patch.object(candidate.urllib.request, "urlopen", side_effect=self.response), self.assertRaises(ValueError):
            candidate.sources(self.version)

    def test_shipped_notice_points_to_actual_source_assets_without_candidate_claims(self):
        notice = self.root / "SOURCES.md"
        notice.write_text("# Native source candidate status\nNot published yet.\n\n# Corresponding source and third-party materials\nSource file 0.1.0-alpha.1.\n")
        candidate.source_notice({"sourceBaseline": self.version, "sourceMaterials": self.assets}, notice)
        text = notice.read_text()
        self.assertNotIn("Not published yet", text)
        self.assertNotIn("0.1.0-alpha.1", text)
        for asset in self.assets:
            self.assertIn(asset["url"], text)
            self.assertIn(asset["sha256"], text)
        notice.write_text("unexpected notice template")
        with self.assertRaises(ValueError):
            candidate.source_notice({"sourceBaseline": self.version}, notice)
