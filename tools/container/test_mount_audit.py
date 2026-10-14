"""A generic kernel error must not be mistaken for confirmed AppArmor evidence."""
import unittest
from collect_mount_audit import verify


class MountAuditTest(unittest.TestCase):
    def test_matching_denial(self):
        log = 'apparmor="DENIED" operation="mount" profile="docker-default" name="/" comm="bwrap"'
        self.assertEqual(log + "\n", verify(log, "bwrap: Failed to make / slave: Permission denied"))

    def test_unrelated_or_missing_evidence(self):
        valid = 'apparmor="DENIED" operation="mount" profile="docker-default" comm="bwrap"'
        for log in ("", valid.replace("DENIED", "ALLOWED"), valid.replace("mount", "open"),
                    valid.replace("docker-default", "other"), valid.replace("bwrap", "other")):
            with self.subTest(log=log), self.assertRaises(ValueError):
                verify(log, "bwrap: Failed to make / slave: Permission denied")
        with self.assertRaises(ValueError):
            verify(valid, "Other failure")


if __name__ == "__main__":
    unittest.main()
