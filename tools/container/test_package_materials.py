import io
import tarfile
import unittest

from review_package_materials import read_member


class NoticeTest(unittest.TestCase):
    def archive(self, links):
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            item = tarfile.TarInfo("doc/gcc-base/copyright")
            content = b"License: GPL-3+ with GCC runtime exception\n"
            item.size = len(content)
            archive.addfile(item, io.BytesIO(content))
            for name, target in links:
                item = tarfile.TarInfo(name)
                item.type = tarfile.SYMTYPE
                item.linkname = target
                archive.addfile(item)
        stream.seek(0)
        return tarfile.open(fileobj=stream)

    def test_package_directory_links_resolve_inside_archive(self):
        with self.archive([("doc/libgcc", "gcc-base")]) as archive:
            self.assertIn(b"runtime exception", read_member(archive, "doc/libgcc/copyright"))

    def test_cycle_and_escape_links_fail(self):
        for links in ([('doc/a', 'a')], [('doc/a', '../../outside')], [('doc/a', '/etc')]):
            with self.archive(links) as archive, self.assertRaises(ValueError):
                read_member(archive, "doc/a/copyright")


if __name__ == "__main__":
    unittest.main()
