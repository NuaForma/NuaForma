"""Real loopback HTTP rehearsal: persistent sockets and bounded independent uploads."""
import hashlib
import http.client
import http.server
import json
import pathlib
import tempfile
import threading
import time
import unittest
import zipfile
from unittest.mock import patch

from nexus_repository import CHECKSUMS, SUFFIXES, publish_bundle


class NexusTransportTest(unittest.TestCase):
    def test_six_module_publication_uses_bounded_persistent_connections(self):
        remote, calls, connections = {}, [], []
        lock = threading.Lock()
        active, peak = 0, 0

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def handle_operation(self):
                nonlocal active, peak
                with lock:
                    active += 1
                    peak = max(peak, active)
                    calls.append((self.command, self.path, self.headers.get("Authorization")))
                try:
                    time.sleep(0.003)
                    status, body = 200, b""
                    if self.command == "PUT":
                        if self.headers.get("Authorization") != "Basic disposable-test-only":
                            status = 401
                        elif self.path in remote:
                            status = 400
                        elif self.path.endswith((".asc", ".md5", ".sha1", ".sha256", ".sha512")) and self.path.rsplit(".", 1)[0] not in remote:
                            status = 409
                        else:
                            remote[self.path] = self.rfile.read(int(self.headers["Content-Length"]))
                            status = 201
                    elif self.path not in remote:
                        status = 404
                    elif self.command == "GET":
                        body = remote[self.path]
                    self.send_response(status)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    if body:
                        self.wfile.write(body)
                        self.wfile.flush()
                finally:
                    with lock:
                        active -= 1

            do_HEAD = do_GET = do_PUT = handle_operation

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        def connection(host, timeout):
            self.assertEqual("repo.nuaforma.dev", host)
            # Only the TLS factory is replaced; the production origin/path policy and
            # HTTP framing, persistence, reads and parallel publication are exercised.
            value = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=timeout)
            connections.append(value)
            return value
        try:
            with tempfile.TemporaryDirectory() as folder:
                root = pathlib.Path(folder)
                bundle, receipt = root / "bundle.zip", root / "receipt.json"
                version = "1.2.3-alpha.1"
                modules = tuple("synthetic-module-" + str(i) for i in range(6))
                hashes, payloads = {}, {}
                for module in modules:
                    for suffix in SUFFIXES:
                        name = f"{module}-{version}{suffix}"
                        relative = f"dev/nuaforma/{module}/{version}/{name}"
                        data = ("approved synthetic bytes " + name).encode()
                        hashes[name] = hashlib.sha256(data).hexdigest()
                        payloads[relative] = data
                        payloads[relative + ".asc"] = b"-----BEGIN PGP SIGNATURE-----\nDISPOSABLE PLACEHOLDER\n"
                        for algorithm in CHECKSUMS:
                            payloads[relative + "." + algorithm] = (hashlib.new(algorithm, data).hexdigest() + "\n").encode()
                with zipfile.ZipFile(bundle, "w") as archive:
                    for name, data in payloads.items():
                        archive.writestr(name, data)
                started = time.monotonic()
                with patch("nexus_repository.http.client.HTTPSConnection", side_effect=connection):
                    result = publish_bundle(bundle, version, modules, hashes,
                                            hashlib.sha256(bundle.read_bytes()).hexdigest(),
                                            "disposable-test-only", receipt)
                elapsed = time.monotonic() - started
                self.assertEqual("published", result["state"])
                self.assertTrue(result["publicBytesMatch"])
                self.assertEqual(174, len(result["uploaded"]))
                self.assertLessEqual(len(connections), 7)
                self.assertGreater(peak, 1)
                self.assertLessEqual(peak, 6)
                self.assertTrue(all(auth is None for method, _, auth in calls if method != "PUT"))
                self.assertEqual(54, len([c for c in calls if c[0] == "GET"]) - 6)
                writes = [(m, p) for m, p, _ in calls if m == "PUT"]
                self.assertEqual(len(writes), len(set(writes)))
                self.assertEqual(378, len(calls))
                self.assertNotIn("disposable-test-only", receipt.read_text())
                print(f"Six-module loopback rehearsal: {len(calls)} HTTP requests, "
                      f"{len(connections)} persistent connections, peak {peak}, {elapsed:.2f}s; no external writes")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
