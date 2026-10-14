"""Qualification must never accept a translated architecture or missing metadata."""
import unittest
from verify_native_platform import verify


class NativePlatformTest(unittest.TestCase):
    def test_native_engines(self):
        for expected, host in (("amd64", "x86_64"), ("amd64", "amd64"), ("arm64", "aarch64"), ("arm64", "arm64")):
            verify({"Os": "linux", "Architecture": expected}, {"OSType": "linux", "Architecture": host}, expected)

    def test_emulated_or_wrong_platform_rejected(self):
        for image, engine, expected in (
            ({"Os": "linux", "Architecture": "amd64"}, {"OSType": "linux", "Architecture": "aarch64"}, "amd64"),
            ({"Os": "linux", "Architecture": "arm64"}, {"OSType": "linux", "Architecture": "x86_64"}, "arm64"),
            ({"Os": "linux", "Architecture": "amd64"}, {"OSType": "linux", "Architecture": "aarch64"}, "arm64"),
            ({"Os": "windows", "Architecture": "amd64"}, {"OSType": "linux", "Architecture": "amd64"}, "amd64"),
            ({"Os": "linux", "Architecture": "arm64"}, {}, "arm64"),
            ({}, {"OSType": "linux", "Architecture": "arm64"}, "arm64"),
        ):
            with self.subTest(image=image, engine=engine), self.assertRaises(ValueError):
                verify(image, engine, expected)


if __name__ == "__main__":
    unittest.main()
