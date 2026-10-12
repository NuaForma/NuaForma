import hashlib
import io
import json
import pathlib
import tarfile
import tempfile
import unittest

from check_image_archive import check


class ArchiveTest(unittest.TestCase):
    def test_exact_archive_and_config_digest_are_both_required(self):
        with tempfile.TemporaryDirectory() as directory:
            file = pathlib.Path(directory) / "nuaforma-container-0.1.0-alpha.1.tar.gz"
            data = json.dumps(dict(os="linux", architecture="amd64", config=dict(User="1000:1000"), rootfs=dict(diff_ids=[]))).encode()
            with tarfile.open(file, "w:gz") as archive:
                item = tarfile.TarInfo("synthetic-config.json")
                item.size = len(data)
                archive.addfile(item, io.BytesIO(data))
            record = dict(containerArchiveSha256=hashlib.sha256(file.read_bytes()).hexdigest(), containerConfigSha256=hashlib.sha256(data).hexdigest())
            self.assertEqual("amd64", check(file, record)["architecture"])
            for field in record:
                with self.assertRaises(AssertionError): check(file, dict(record, **{field: "0" * 64}))


if __name__ == "__main__":
    unittest.main()
