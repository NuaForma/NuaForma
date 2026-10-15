import copy
import hashlib
import io
import json
import pathlib
import tempfile
import unittest
import urllib.error

from central_portal import upload
from central_publish import publish
from ensure_tag_absent import check as absent_tag
from validate_release_gate import check
from verify_libraries import MODULES


class AlphaGateTest(unittest.TestCase):
    def setUp(self):
        self.commit = "a" * 40
        self.record = {"version": "0.1.0-alpha.1", "sourceCommit": self.commit, "qualifiedImage": "sha256:" + "b" * 64,
            "reviewer": "mrhoeve", "latest": False, "containerArchiveSha256": "f" * 64, "containerConfigSha256": "e" * 64,
            "approved": dict.fromkeys(("libraryAudit", "containerAudit", "sourceAccess", "fidelityQualification", "securityReview"), True),
            "sourceMaterials": [{"url": "https://github.com/NuaForma/NuaForma/releases/download/v0.1.0-alpha.1/sources.tar.xz",
                                 "sha256": "c" * 64, "verifiedAnonymousAccess": True}],
            "libraryHashes": {module + "-0.1.0-alpha.1" + suffix: "d" * 64 for module in MODULES
                              for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")}}

    def test_exact_approved_alpha_only(self):
        check(self.record, "0.1.0-alpha.1", self.commit)
        for field in self.record["approved"]:
            changed = copy.deepcopy(self.record)
            changed["approved"][field] = False
            with self.assertRaises(AssertionError): check(changed, "0.1.0-alpha.1", self.commit)
        for key, value in (("latest", True), ("version", "0.1.0"), ("sourceCommit", "e" * 40), ("sourceMaterials", []), ("libraryHashes", {})):
            with self.subTest(key=key), self.assertRaises(AssertionError):
                check(dict(self.record, **{key: value}), "0.1.0-alpha.1", self.commit)

    def test_source_access_and_hashes_are_not_optional(self):
        changed = copy.deepcopy(self.record)
        changed["sourceMaterials"][0]["verifiedAnonymousAccess"] = False
        with self.assertRaises(AssertionError): check(changed, "0.1.0-alpha.1", self.commit)
        changed["sourceMaterials"][0]["url"] = "https://example.invalid/sources"
        with self.assertRaises(AssertionError): check(changed, "0.1.0-alpha.1", self.commit)


class PortalTest(unittest.TestCase):
    def test_registry_absence_must_be_explicit_without_authentication_or_network_failure(self):
        absent_tag(1, "manifest unknown")
        for code, output in ((0, "existing manifest"), (1, "denied: manifest unknown"), (1, "unauthorized"), (1, "connection reset")):
            with self.assertRaises(AssertionError): absent_tag(code, output)

    def test_final_publish_requires_validated_exact_coordinates_and_artifact_bytes(self):
        identity = "12345678-1234-1234-1234-123456789abc"
        content = b"synthetic staged artifact"
        version = "0.2.0-alpha.1"
        review = dict(version=version, libraryHashes={module + "-" + version + suffix: hashlib.sha256(content).hexdigest()
            for module in MODULES for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")})
        state = dict(deploymentId=identity, deploymentState="VALIDATED",
                     purls=["pkg:maven/dev.nuaforma/" + module + "@" + version for module in MODULES])
        requests = []
        def opener(request, timeout):
            requests.append(request)
            data = json.dumps(state).encode() if "/status?" in request.full_url else content if "/download/" in request.full_url else b""
            response = io.BytesIO(data)
            response.status = 204 if request.full_url.endswith(identity) else 200
            return response
        result = publish(identity, review, "synthetic-token", opener)
        self.assertTrue(result["publishRequested"])
        self.assertFalse(result["published"])
        self.assertEqual(22, len(requests))
        requests.clear()
        state["deploymentState"] = "VALIDATING"
        with self.assertRaises(AssertionError): publish(identity, review, "synthetic-token", opener)
        self.assertEqual(1, len(requests))
        state["deploymentState"] = "VALIDATED"
        requests.clear()
        changed = dict(review, libraryHashes=dict(review["libraryHashes"]))
        changed["libraryHashes"][next(iter(changed["libraryHashes"]))] = "0" * 64
        with self.assertRaises(AssertionError): publish(identity, changed, "synthetic-token", opener)
        self.assertEqual(2, len(requests))
        self.assertTrue(requests[-1].full_url.find("/download/") >= 0)
        requests.clear()
        changed["libraryHashes"].pop(next(iter(changed["libraryHashes"])))
        with self.assertRaises(AssertionError): publish(identity, changed, "synthetic-token", opener)
        self.assertEqual([], requests)

    def test_explicit_upload_uses_user_managed_mode_and_records_identity_without_publishing(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = pathlib.Path(directory) / "bundle.zip"
            bundle.write_bytes(b"synthetic request fixture, not a valid Central bundle")
            identity = "12345678-1234-1234-1234-123456789abc"
            requests = []
            def opener(request, timeout):
                requests.append(request)
                response = io.BytesIO(identity.encode())
                response.status = 201
                return response
            digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
            record = upload(bundle, "0.1.0-alpha.1", digest, "synthetic-token", opener)
            self.assertEqual(identity, record["deploymentId"])
            self.assertFalse(record["published"])
            self.assertEqual(1, len(requests))
            self.assertIn("publishingType=USER_MANAGED", requests[0].full_url)
            self.assertEqual("Bearer synthetic-token", requests[0].get_header("Authorization"))
            self.assertIn(b'name="bundle"', requests[0].data)
            with self.assertRaises(AssertionError): upload(bundle, "0.1.0-alpha.1", "0" * 64, "synthetic-token", opener)
            self.assertEqual(1, len(requests))

    def test_http_failure_is_sanitized_and_never_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = pathlib.Path(directory) / "bundle.zip"
            bundle.write_bytes(b"synthetic fixture")
            calls = []
            def opener(request, timeout):
                calls.append(request)
                raise urllib.error.HTTPError(request.full_url, 403, "synthetic private diagnostic", {}, None)
            with self.assertRaisesRegex(RuntimeError, "HTTP 403; no automatic retry"):
                upload(bundle, "0.1.0-alpha.1", hashlib.sha256(bundle.read_bytes()).hexdigest(), "synthetic-token", opener)
            self.assertEqual(1, len(calls))


if __name__ == "__main__": unittest.main()
