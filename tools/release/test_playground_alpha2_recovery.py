import copy
import unittest

from check_playground_alpha2_recovery import AMD64_DIGEST, SOURCE, VERSION, check


class PlaygroundRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.review = dict(version=VERSION, sourceCommit=SOURCE, application="playground",
                           platforms={"amd64": {"containerConfigSha256": "a" * 64}})
        self.details = {"Descriptor": {"digest": AMD64_DIGEST, "platform": {"os": "linux", "architecture": "amd64"}},
                        "SchemaV2Manifest": {"schemaVersion": 2, "config": {"digest": "sha256:" + "a" * 64}}}

    def test_exact_existing_platform(self):
        check(self.review, self.details)
        details = copy.deepcopy(self.details)
        details["OCIManifest"] = details.pop("SchemaV2Manifest")
        check(self.review, details)

    def test_different_application_version_or_source_fails(self):
        for field, value in (("version", "0.2.0-alpha.3"), ("application", "server"), ("sourceCommit", "b" * 40)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                check(dict(self.review, **{field: value}), self.details)

    def test_changed_descriptor_platform_or_config_fails(self):
        for target, field, value in (("Descriptor", "digest", "sha256:" + "b" * 64),
                                     ("Descriptor", "platform", {"os": "linux", "architecture": "arm64"}),
                                     ("SchemaV2Manifest", "config", {"digest": "sha256:" + "b" * 64}),
                                     ("SchemaV2Manifest", "schemaVersion", 1)):
            details = copy.deepcopy(self.details)
            details[target][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                check(self.review, details)
        with self.assertRaises(ValueError):
            check(self.review, {})
