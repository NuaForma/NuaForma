"""Offline routing and Maven protocol regressions; never contact a publishing service."""
import hashlib
import io
import json
import pathlib
import tempfile
import unittest
import urllib.error
import urllib.request
import zipfile

from maven_destination import CENTRAL, PRERELEASES, SNAPSHOTS, destination, stable_version
from nexus_repository import CHECKSUMS, NoRedirect, SUFFIXES, publish_bundle, token, updated_metadata
from snapshot_gate import successful_checks
from verify_snapshot import resolved_versions


class Response(io.BytesIO):
    def __init__(self, data=b"", status=200, headers=None):
        super().__init__(data)
        self.status, self.headers = status, headers or {}


class NexusPublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name)
        self.version, self.module = "1.2.3-alpha.1", "synthetic-library"
        self.bundle = self.root / "reviewed.zip"
        self.receipt = self.root / "receipt.json"
        self.hashes, self.files, self.remote, self.calls = {}, {}, {}, []
        for suffix in SUFFIXES:
            name = f"{self.module}-{self.version}{suffix}"
            path = f"dev/nuaforma/{self.module}/{self.version}/{name}"
            data = ("synthetic approved " + name).encode()
            self.hashes[name] = hashlib.sha256(data).hexdigest()
            self.files[path] = data
            self.files[path + ".asc"] = b"-----BEGIN PGP SIGNATURE-----\nDISPOSABLE TEST PLACEHOLDER\n"
            for algorithm in CHECKSUMS:
                self.files[path + "." + algorithm] = (hashlib.new(algorithm, data).hexdigest() + "\n").encode()
        self.repack()

    def repack(self):
        with zipfile.ZipFile(self.bundle, "w") as archive:
            for name, data in self.files.items():
                archive.writestr(name, data)
        self.digest = hashlib.sha256(self.bundle.read_bytes()).hexdigest()

    def transport(self, operation, timeout):
        self.calls.append(operation)
        name = operation.full_url.removeprefix(PRERELEASES)
        self.assertNotEqual(name, operation.full_url)
        if operation.method == "PUT":
            self.assertEqual("Basic synthetic-authorization", operation.get_header("Authorization"))
            self.remote[name] = operation.data
            return Response(status=201)
        self.assertIsNone(operation.get_header("Authorization"))
        if name not in self.remote:
            raise urllib.error.HTTPError(operation.full_url, 404, "absent", {}, None)
        return Response(b"" if operation.method == "HEAD" else self.remote[name], headers={"ETag": '"original-index"'})

    def publish(self, transport=None):
        return publish_bundle(self.bundle, self.version, (self.module,), self.hashes, self.digest,
                              "synthetic-authorization", self.receipt, opener=transport or self.transport)

    def test_success_uploads_exact_signed_bytes_and_merges_existing_metadata(self):
        metadata = f"dev/nuaforma/{self.module}/maven-metadata.xml"
        self.remote[metadata] = updated_metadata(None, self.module, "1.2.3-alpha.0")
        result = self.publish()
        self.assertTrue(result["publicBytesMatch"])
        self.assertEqual("published", result["state"])
        self.assertEqual(4, result["artifacts"])
        for name, data in self.files.items():
            self.assertEqual(data, self.remote[name])
        self.assertIn(b"1.2.3-alpha.0", self.remote[metadata])
        self.assertIn(self.version.encode(), self.remote[metadata])
        metadata_write = next(c for c in self.calls if c.method == "PUT" and c.full_url.endswith("maven-metadata.xml"))
        self.assertEqual('"original-index"', metadata_write.get_header("If-match"))
        self.assertEqual(len(self.files) + 5, len([c for c in self.calls if c.method == "PUT"]))
        self.assertEqual(result, json.loads(self.receipt.read_text()))
        self.assertNotIn("synthetic-authorization", self.receipt.read_text())

    def test_existing_artifact_metadata_or_receipt_prevents_any_write(self):
        for kind in ("artifact", "metadata", "receipt"):
            with self.subTest(kind=kind):
                self.remote.clear()
                self.calls.clear()
                if self.receipt.exists():
                    self.receipt.unlink()
                if kind == "artifact":
                    self.remote[next(iter(self.files))] = b"already published"
                elif kind == "metadata":
                    self.remote[f"dev/nuaforma/{self.module}/maven-metadata.xml"] = updated_metadata(None, self.module, self.version)
                else:
                    self.receipt.write_text("retained previous operation")
                with self.assertRaises((ValueError, FileExistsError)):
                    self.publish()
                self.assertFalse(any(c.method == "PUT" for c in self.calls))

    def test_changed_bundle_artifact_missing_signature_and_path_injection_fail_before_network(self):
        original = dict(self.files)
        for mutation in ("bundle", "artifact", "signature", "path"):
            with self.subTest(mutation=mutation):
                self.files = dict(original)
                if mutation == "artifact":
                    self.files[next(iter(self.files))] = b"changed artifact"
                elif mutation == "signature":
                    del self.files[next(name for name in self.files if name.endswith(".asc"))]
                elif mutation == "path":
                    self.files["../../escape"] = b"unsafe"
                self.repack()
                if mutation == "bundle":
                    self.bundle.write_bytes(self.bundle.read_bytes() + b"changed reviewed zip")
                with self.assertRaises(ValueError):
                    self.publish()
                self.assertEqual([], self.calls)

    def test_uncertain_write_is_not_retried_and_pending_path_is_retained(self):
        writes = []
        def transport(operation, timeout):
            if operation.method == "PUT":
                writes.append(operation.full_url)
                if len(writes) == 2:
                    raise urllib.error.URLError("SYNTHETIC_SECRET_MUST_NOT_BE_LOGGED")
            return self.transport(operation, timeout)
        with self.assertRaises(RuntimeError) as caught:
            self.publish(transport)
        self.assertNotIn("SYNTHETIC_SECRET", str(caught.exception))
        self.assertEqual(2, len(writes))
        receipt = json.loads(self.receipt.read_text())
        self.assertEqual(1, len(receipt["uploaded"]))
        self.assertTrue(receipt["pending"])
        self.assertFalse(receipt["publicBytesMatch"])

    def test_auth_failure_and_redirect_are_not_treated_as_absence_or_retried(self):
        for status in (301, 302, 307, 308, 401, 403, 429, 500):
            calls = []
            def transport(operation, timeout):
                calls.append(operation)
                raise urllib.error.HTTPError(operation.full_url, status, "SYNTHETIC_SECRET", {}, None)
            with self.subTest(status=status), self.assertRaises(RuntimeError) as caught:
                self.publish(transport)
            self.assertNotIn("SYNTHETIC_SECRET", str(caught.exception))
            self.assertEqual(1, len(calls))
            self.assertEqual("HEAD", calls[0].method)
            self.assertFalse(self.receipt.exists())
        with self.assertRaises(urllib.error.HTTPError):
            NoRedirect().redirect_request(urllib.request.Request(PRERELEASES), None, 302, "redirect", {}, "https://elsewhere.invalid/")

    def test_parallel_failure_retains_all_in_flight_outcomes_and_never_retries_writes(self):
        failed = sorted(self.files)[1]
        writes = []
        def transport(operation, timeout):
            if operation.method == "PUT":
                name = operation.full_url.removeprefix(PRERELEASES)
                writes.append(name)
                if name == failed:
                    raise urllib.error.URLError("SYNTHETIC_SECRET_MUST_NOT_BE_LOGGED")
            return self.transport(operation, timeout)
        with self.assertRaises(RuntimeError) as caught:
            publish_bundle(self.bundle, self.version, (self.module,), self.hashes, self.digest,
                           "synthetic-authorization", self.receipt, opener=transport, workers=6)
        self.assertNotIn("SYNTHETIC_SECRET", str(caught.exception))
        self.assertEqual(10, len(writes))
        self.assertEqual(len(writes), len(set(writes)))
        record = json.loads(self.receipt.read_text())
        self.assertEqual([failed], record["pending"])
        self.assertEqual(9, len(record["uploaded"]))
        self.assertFalse(record["publicBytesMatch"])

    def test_anonymous_changed_bytes_fail_after_publication_without_reupload(self):
        def transport(operation, timeout):
            response = self.transport(operation, timeout)
            if operation.method == "GET" and operation.full_url.endswith(".jar"):
                return Response(b"incorrect public artifact")
            return response
        with self.assertRaisesRegex(ValueError, "Anonymous Nexus bytes"):
            self.publish(transport)
        self.assertEqual(len(self.files) + 5, len([c for c in self.calls if c.method == "PUT"]))
        self.assertEqual("verifying", json.loads(self.receipt.read_text())["state"])

    def test_unsafe_or_wrong_coordinate_metadata_fails_before_writes(self):
        for data in (b'<!DOCTYPE metadata [<!ENTITY x SYSTEM "file:///secret">]><metadata/>',
                     b"<metadata><groupId>other.group</groupId></metadata>"):
            with self.assertRaises(ValueError):
                updated_metadata(data, self.module, self.version)


class MavenRoutingTest(unittest.TestCase):
    def test_only_stable_versions_reach_central(self):
        for version in ("0.0.0", "1.2.3", "10.22.333"):
            self.assertEqual({"kind": "stable", "repository": CENTRAL}, destination(version))
            self.assertEqual(version, stable_version(version))
        for version in ("1.2.3-alpha.1", "1.2.3-beta.2", "1.2.3-rc.1", "1.2.3-0", "1.2.3-SNAPSHOT"):
            with self.assertRaises(ValueError):
                stable_version(version)
            self.assertEqual(SNAPSHOTS if version.endswith("-SNAPSHOT") else PRERELEASES, destination(version)["repository"])
        for version in (None, "", "v1.2.3", "01.2.3", "1.2.3-01", "1.2.3+build", "1.2.3-snapshot", "1.2.3-alpha-SNAPSHOT", "1.2.3\n", "../1.2.3"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                destination(version)

    def test_snapshot_ci_does_not_accept_fake_sonar_or_older_success(self):
        checks = [{"id": index, "name": name, "app": {"slug": app}, "status": "completed", "conclusion": "success"}
                  for index, (name, app) in enumerate((("Build (Java 17)", "github-actions"), ("Build (Java 25)", "github-actions"),
                                                     ("SonarCloud Code Analysis", "sonarqubecloud")))]
        successful_checks(checks, sonar=True)
        successful_checks(checks[:2], sonar=False)
        with self.assertRaises(ValueError):
            successful_checks(checks[:2], sonar=True)
        with self.assertRaises(ValueError):
            successful_checks(checks + [dict(checks[0], id=99, conclusion="failure")])
        checks[-1]["app"]["slug"] = "github-actions"
        with self.assertRaises(ValueError):
            successful_checks(checks, sonar=True)

    def test_snapshot_metadata_requires_one_consistent_safe_timestamp_generation(self):
        value = "1.2.3-20261030.120000-1"
        items = "".join(f"<snapshotVersion><extension>{extension}</extension><classifier>{classifier}</classifier><value>{value}</value></snapshotVersion>"
                        for extension, classifier in (("pom", ""), ("jar", ""), ("jar", "sources"), ("jar", "javadoc")))
        metadata = f"<metadata><version>1.2.3-SNAPSHOT</version><versioning><snapshotVersions>{items}</snapshotVersions></versioning></metadata>".encode()
        self.assertEqual({value}, set(resolved_versions(metadata, "1.2.3-SNAPSHOT").values()))
        module_entry = f"<snapshotVersion><extension>module</extension><value>{value}</value></snapshotVersion>".encode()
        with_module = metadata.replace(b"</snapshotVersions>", module_entry + b"</snapshotVersions>")
        self.assertEqual(value, resolved_versions(with_module, "1.2.3-SNAPSHOT")[".module"])
        for bad in (metadata.replace(value.encode(), b"../../secret", 1), metadata.replace(b"-1</value>", b"-2</value>", 1),
                    metadata.replace(b"<classifier>sources</classifier>", b"<classifier>other</classifier>")):
            with self.assertRaises(ValueError):
                resolved_versions(bad, "1.2.3-SNAPSHOT")


if __name__ == "__main__":
    unittest.main()
